package com.github.dmitescu.styx.event

import cats.effect.ExitCase.Error
import com.github.dmitescu.styx.cache.{DataSourceRemovable, EventCache}
import com.github.dmitescu.styx.model._

import cats.effect._
import cats.effect.implicits._
import cats.effect.concurrent._
import fetch.DataSource
import org.specs2.execute.{AsResult, Result}

import org.specs2.specification._
import org.specs2.mutable.Specification
import org.specs2.mock.Mockito

import java.io.BufferedWriter
import scala.concurrent.SyncVar
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class EventProcessorIntegrationSpec(implicit ec: ExecutionContext)
    extends Specification
    with ForEach[
      (SyncVar[Map[Int, MVar[IO, Payload]]], EventCache[IO], Ref[IO, Map[Int, Seq[Int]]])
    ]
    with Mockito {

  override def foreach[R: AsResult](
      f: ((SyncVar[Map[Int, MVar[IO, Payload]]], EventCache[IO], Ref[IO, Map[Int, Seq[Int]]])) => R
  ): Result = {
    val topics = Map[Int, MVar[IO, Payload]](
      1 -> mock[MVar[IO, Payload]],
      2 -> mock[MVar[IO, Payload]],
      3 -> mock[MVar[IO, Payload]]
    )

    topics.values.map(_.put(any[Payload]) returns IO.unit)

    val topicReg: SyncVar[Map[Int, MVar[IO, Payload]]] = new SyncVar
    topicReg.put(topics)

    val noopCache = mock[EventCache[IO]]

    noopCache.remove(any, any)(any[ConcurrentEffect[IO]]) returns IO.pure(noopCache)

    val followers = (for {
      r <- Ref[IO].of(Map(1 -> Seq(2, 3)))
    } yield r).unsafeRunSync

    AsResult(f(topicReg, noopCache, followers))
  }

  def processEvents(
      topicReg: SyncVar[Map[Int, MVar[IO, Payload]]],
      noopCache: EventCache[IO],
      followers: Ref[IO, Map[Int, Seq[Int]]]
  ) = {
    implicit val cf = IO.contextShift(ec)

    val p = for {
      _ <- EventProcessor.processEvent(topicReg, followers, noopCache)
    } yield ()

    p.unsafeRunAsyncAndForget
  }

  "Event processing should" >> {
    "read cache once" >> {
      t: (SyncVar[Map[Int, MVar[IO, Payload]]], EventCache[IO], Ref[IO, Map[Int, Seq[Int]]]) =>
        def topicReg  = t._1
        def cache     = t._2
        def followers = t._3

        givenCacheEmpty(cache)

        processEvents(topicReg, cache, followers)

        there was atLeastOne(cache).fetch(any)
    }

    "send broadcast to all clients" >> {
      t: (SyncVar[Map[Int, MVar[IO, Payload]]], EventCache[IO], Ref[IO, Map[Int, Seq[Int]]]) =>
        def topicReg  = t._1
        def cache     = t._2
        def followers = t._3

        givenCacheReturnsBroadcastEvent(cache)

        processEvents(topicReg, cache, followers)

        there was atLeastOne(topicReg.get.get(1).get).put(Payload("1|B"))
        there was atLeastOne(topicReg.get.get(2).get).put(Payload("1|B"))
        there was atLeastOne(topicReg.get.get(3).get).put(Payload("1|B"))
    }
    "send private message to sender and receiver" >> {
      t: (SyncVar[Map[Int, MVar[IO, Payload]]], EventCache[IO], Ref[IO, Map[Int, Seq[Int]]]) =>
        def topicReg  = t._1
        def cache     = t._2
        def followers = t._3

        givenCacheReturnsPrivateMessageEvent(cache)

        processEvents(topicReg, cache, followers)

        there was no(topicReg.get.get(1).get).put(any)
        there was atLeastOne(topicReg.get.get(2).get).put(Payload("1|P|1|2"))
        there was no(topicReg.get.get(3).get).put(any)
    }

    "send status update to followers only" >> {
      t: (SyncVar[Map[Int, MVar[IO, Payload]]], EventCache[IO], Ref[IO, Map[Int, Seq[Int]]]) =>
        def topicReg  = t._1
        def cache     = t._2
        def followers = t._3

        givenCacheReturnsStatusEvent(cache)

        processEvents(topicReg, cache, followers)

        there was no(topicReg.get.get(1).get).put(any)
        there was atLeastOne(topicReg.get.get(2).get).put(Payload("1|S|1"))
        there was atLeastOne(topicReg.get.get(3).get).put(Payload("1|S|1"))
    }

    "should send follower payload to the followed user" >> {
      t: (SyncVar[Map[Int, MVar[IO, Payload]]], EventCache[IO], Ref[IO, Map[Int, Seq[Int]]]) =>
        def topicReg  = t._1
        def cache     = t._2
        def followers = t._3

        givenCacheReturnsFollowEvent(cache)

        processEvents(topicReg, cache, followers)

        there was no(topicReg.get.get(1).get).put(any)
        there was atLeastOne(topicReg.get.get(2).get).put(Payload("1|F|1|2"))
        there was no(topicReg.get.get(3).get).put(any)
    }
  }

  val eventBroadcast      = EventBroadcast(1)
  val eventPrivateMessage = EventPrivateMessage(1, 1, 2)
  val eventFollow         = EventFollow(1, 1, 2)
  val eventUnfollow       = EventUnfollow(1, 1, 2)
  val eventStatus         = EventStatusUpdate(1, 1)

  def givenCacheEmpty(cache: EventCache[IO]) =
    cache.fetch(any) returns Sync[IO].pure(Some(Seq()))
  def givenCacheReturnsBroadcastEvent(cache: EventCache[IO]) =
    cache.fetch(any) returns Sync[IO]
      .pure(Some(Seq(eventBroadcast)))
  def givenCacheReturnsPrivateMessageEvent(cache: EventCache[IO]) =
    cache.fetch(any) returns Sync[IO]
      .pure(Some(Seq(eventPrivateMessage)))
  def givenCacheReturnsFollowEvent(cache: EventCache[IO]) =
    cache.fetch(any) returns Sync[IO]
      .pure(Some(Seq(eventFollow)))
  def givenCacheReturnsUnfollowEvent(cache: EventCache[IO]) =
    cache.fetch(any) returns Sync[IO]
      .pure(Some(Seq(eventUnfollow)))
  def givenCacheReturnsStatusEvent(cache: EventCache[IO]) =
    cache.fetch(any) returns Sync[IO]
      .pure(Some(Seq(eventStatus)))
}
