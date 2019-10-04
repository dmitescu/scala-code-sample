package com.github.dmitescu.styx.cache

import com.github.dmitescu.styx.{Configuration, LogSupport}
import com.github.dmitescu.styx.model.{Payload, EventType}
import com.github.dmitescu.styx.model.eventTypeImplicits._

import fetch._

import cats._
import cats.effect._
import cats.implicits._
import cats.derived._

import scala.util.Random
import scala.collection.immutable.SortedSet
import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/*
 * The main motivation for using a memory cache is that, for realistic situations
 * you would like to store the events in a database or have a noop datasource for tests;
 * this way, the processor can be easily configured
 */

// Ideally this would be used a universal cache (see the `fetch` library) not as a data source, but
// using shapeless this could be mitigated by defining a Poly1
final class DataSourceName(val name: String)             extends AnyVal
final class DataSourceResult(val result: SortedSet[Any]) extends AnyVal

trait DataSourceRemovable[K, V] {
  def remove[F[_]: ConcurrentEffect](tp: K, v: Seq[V]): F[EventCache[F]]
}

case class EventCache[F[_]: ConcurrentEffect]()
    extends DataSource[F, String, Seq[EventType]]
    with DataSourceRemovable[String, EventType]
    with LogSupport {
  override def data = MemoryEventCache

  override def CF = ConcurrentEffect[F]

  override def fetch(name: String): F[Option[Seq[EventType]]] =
    Applicative[F].pure({
      log.debug(s"looking ${name.toString}")
      MemoryEventCache.state
        .get(new DataSourceName(name))
        .map(_.result.toSeq.asInstanceOf[Seq[EventType]])
    })

  override def remove[F[_]: ConcurrentEffect](tp: String, v: Seq[EventType]): F[EventCache[F]] =
    MemoryEventCache.remove[F](tp, v)
}

trait EventCacheT {
  def insertU[F[_]: ConcurrentEffect](tp: String, v: EventType): F[Unit]
  def get[F[_]: ConcurrentEffect]: F[EventCache[F]]
}

object MemoryEventCache extends Data[String, Seq[EventType]] with LogSupport with EventCacheT {
  val state = new TrieMap[DataSourceName, DataSourceResult]()

  private final val MAX_STORE_SIZE = Configuration.cacheSize
  private final val CLEANUP_FACTOR = Configuration.cleanupFactor

  def source[F[_]: ConcurrentEffect]: EventCache[F] = {
    EventCache()
  }

  override def name = "MemoryEventCache"

  // tune this for how often the cache is checked
  def randomBool =
    Seq
      .range(0, CLEANUP_FACTOR)
      .foldLeft(true) { (a, _) =>
        a && Random.nextBoolean
      }

  def flush[F[_]: ConcurrentEffect](results: (String, EventType)*): EventCache[F] = {
    results
      .foldLeft(Map[String, Seq[EventType]]())({
        case (acc, (s, v)) =>
          acc.get(s) match {
            case Some(l) => acc ++ Map(s -> (l ++ Seq(v)))
            case None    => Map(s        -> Seq(v))
          }
      })
      .map {
        case (k, vl) =>
          this.state.put(
            new DataSourceName(k),
            new DataSourceResult(SortedSet(vl: _*).asInstanceOf[SortedSet[Any]])
          )
      }

    EventCache()
  }

  def remove[F[_]: ConcurrentEffect](tp: String, v: Seq[EventType]): F[EventCache[F]] = {
    val cache = EventCache()
    for {
      maybeEvents <- cache.fetch(tp)
      restEvents  <- Sync[F].delay(maybeEvents.fold(Seq[EventType]())(_.filterNot(v.contains(_))))
      _ <- Sync[F].delay(
            this.state.put(
              new DataSourceName(tp),
              new DataSourceResult(SortedSet(restEvents: _*).asInstanceOf[SortedSet[Any]])
            )
          )
    } yield cache
  }

  def insert[F[_]: ConcurrentEffect](tp: String, v: EventType): F[EventCache[F]] =
    Applicative[F].pure({
      val items = state.get(new DataSourceName(tp))
      val newItems = if (randomBool) {
        items.map { r =>
          // Note that the requirements did specify about memory limit
          // so this can be used to tune that
          if (r.result.size > MAX_STORE_SIZE) {
            log.warn("cleaning up event cache")
            val R = r.result.takeRight(MAX_STORE_SIZE)
            state.put(new DataSourceName(tp), new DataSourceResult(R))
            R
          } else {
            r.result
          }
        }
      } else {
        items.map(_.result)
      }

      state.put(
        new DataSourceName(tp),
        new DataSourceResult(newItems match {
          case Some(items) =>
            (items.size < MAX_STORE_SIZE) match {
              case true  => items + v
              case false => items.tail + v
            }
          case None => SortedSet(v).asInstanceOf[SortedSet[Any]]
        })
      )

      EventCache()
    })

  override def insertU[F[_]: ConcurrentEffect](tp: String, v: EventType): F[Unit] =
    insert[F](tp, v) *> Sync[F].unit

  override def get[F[_]: ConcurrentEffect] = Sync[F].pure(EventCache[F]())
}
