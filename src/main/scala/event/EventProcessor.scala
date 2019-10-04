package com.github.dmitescu.styx.event

import com.github.dmitescu.styx.LogSupport
import com.github.dmitescu.styx.Configuration
import com.github.dmitescu.styx.cache.MemoryEventCache
import com.github.dmitescu.styx.model._
import com.github.dmitescu.styx.cache._

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.syntax.all._
import cats.effect.ExitCase._

import cats.implicits._
import cats.derived._
import java.util.concurrent.Executors
import scala.concurrent.{Channel, ExecutionContext, SyncVar}

import shapeless._

import fetch._

import scala.concurrent.duration._
import java.io.{BufferedReader, InputStreamReader, BufferedWriter, PrintWriter}
import java.nio.channels.AsynchronousChannelGroup
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}

/*
 * Event processor class
 *
 * Could definately be refactored into a class'd and traited version,
 * for reuse and ability to easily extend it, and implement
 *
 */
object EventProcessor extends LogSupport {
  import Configuration.Implicits._

  def createListeningSocket =
    new ServerSocket(
      Configuration.clientsPort,
      Configuration.maxClients,
      InetAddress.getByName(Configuration.listeningAddress)
    )

  def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
    Sync[F]
      .delay(socket.close())
      .handleErrorWith(_ => Sync[F].delay(log.warn("dropped connection")))

  /*
   * returns the effect of processing the events in the
   * cache, and sending them on the correct topic; is
   * responsible for the distribution and enrichment
   */
  def processEvent[F[_]: ConcurrentEffect: ContextShift](
      topicReg: SyncVar[Map[Int, MVar[F, Payload]]],
      followers: Ref[F, Map[Int, Seq[Int]]],
      cache: EventCache[F]
  ): F[Unit] = {
    import encoderImplicits._
    import decoderImplicits._
    import dtoImplicits._

    for {
      events   <- cache.fetch("ALL_EVENTS")
      topicMap <- Sync[F].delay(topicReg.get)

      processedEvents <- events.fold(Sync[F].pure(List[EventType]())) {
                          _.toList
                            .foldLeftM(List[EventType]()) { (a, e: EventType) =>
                              Sync[F].unit *> {
                                e match {
                                  case e @ EventFollow(id, from, clientId) =>
                                    for {
                                      _ <- topicMap
                                            .get(clientId)
                                            .map(_.put(EventDTO.from(e).encode))
                                            .getOrElse(
                                              Sync[F].delay(
                                                log.warn(s"could not find topic for ${e.toString}")
                                              )
                                            )
                                      _ <- followers.update { fl =>
                                            fl.get(from) match {
                                              case Some(fll) =>
                                                fll.contains(clientId) match {
                                                  case true => fl
                                                  case false =>
                                                    fl ++ Map(from -> (fll ++ Seq(clientId)))
                                                }
                                              case None => fl ++ Map(from -> Seq(clientId))
                                            }
                                          }
                                    } yield (a ++ Seq(e))
                                  case e @ EventUnfollow(id, from, to) =>
                                    for {
                                      _ <- followers.update { fl =>
                                            fl.get(from) match {
                                              case Some(fll) =>
                                                fl ++ Map(from -> fll.filterNot(_ != to))
                                              case None => fl
                                            }
                                          }
                                    } yield (a ++ Seq(e))
                                  case e @ EventBroadcast(id) =>
                                    for {
                                      _ <- (topicMap.values.foldLeft(Sync[F].unit) { (a, v) =>
                                            a >> v.put(EventDTO.from(e).encode)
                                          })
                                    } yield (a ++ Seq(e))
                                  case e @ EventPrivateMessage(id, _, clientId) =>
                                    for {
                                      _ <- topicMap
                                            .get(clientId)
                                            .map(_.put(EventDTO.from(e).encode))
                                            .getOrElse(
                                              Sync[F].delay(
                                                log.info(s"could not find topic for ${e.toString}")
                                              )
                                            )
                                    } yield (a ++ Seq(e))
                                  case e @ EventStatusUpdate(id, from) =>
                                    for {
                                      followerData <- followers.get
                                      _ <- followerData.get(from).toSeq.foldLeft(Sync[F].unit) {
                                            (a, s) =>
                                              a >> s.foldLeft(Sync[F].unit) { (as, ss) =>
                                                as >> topicMap
                                                  .get(ss)
                                                  .map(_.put(EventDTO.from(e).encode))
                                                  .getOrElse(
                                                    Sync[F].delay(
                                                      log.warn(
                                                        s"could not find topic for ${e.toString}"
                                                      )
                                                    )
                                                  )
                                              }
                                          }
                                    } yield (a ++ Seq(e))
                                  case _ => Sync[F].pure(a)
                                }
                              }
                            }
                        }
      _ <- processedEvents.isEmpty match {
            case true  => Sync[F].delay(Thread.sleep(Configuration.batchInterval))
            case false => Sync[F].unit
          }
      _ <- cache.remove("ALL_EVENTS", processedEvents)
      _ <- ContextShift[F].shift *> processEvent(topicReg, followers, cache)
    } yield ()
  }

  def createEventProcessor[F[_]: ConcurrentEffect: ContextShift](
      topicReg: SyncVar[Map[Int, MVar[F, Payload]]],
      followers: Ref[F, Map[Int, Seq[Int]]]
  ): F[Unit] =
    for {
      cache <- MemoryEventCache.get
      _     <- Sync[F].delay(log.info("running processor"))
      _     <- processEvent(topicReg, followers, cache)
    } yield ()

  /*
   *  Main execution point; joins the event processor and the
   *  publishing stream
   */
  def run[F[_]: ConcurrentEffect: ContextShift: Timer] = {
    def l = implicitly[LiftIO[F]]

    for {
      followers <- Ref.of[F, Map[Int, Seq[Int]]](Map())
      e <- Sync[F]
            .delay(createListeningSocket)
            .bracketCase { s =>
              for {
                topicRegistry <- Sync[F].delay(new SyncVar[Map[Int, MVar[F, Payload]]])
                _             = topicRegistry.put(Map.empty)

                registry <- Ref.of[F, Map[Int, Resource[F, BufferedWriter]]](
                             Map.empty[Int, Resource[F, BufferedWriter]]
                           )

                f1 <- createEventProcessor(topicRegistry, followers).start
                f2 <- PublisherStream.subscribe(s, topicRegistry, registry).start
                _  <- f1.join
                _  <- f2.join

                code <- Sync[F].pure(ExitCode.Success)

              } yield code
            } { (s, e) =>
              (e match {
                case Completed => Sync[F].unit
                case Canceled  => Sync[F].delay(log.warn("event processor cancelled"))
                case Error(e)  => Sync[F].delay(log.warn(e.toString))
              }) >> close[F](s)
            }
    } yield e
  }
}
