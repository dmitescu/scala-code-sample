package com.github.dmitescu.styx.event

import com.github.dmitescu.styx.LogSupport
import com.github.dmitescu.styx.Configuration
import com.github.dmitescu.styx.model._
import com.github.dmitescu.styx.cache._

import cats._
import cats.effect._
import cats.effect.syntax.all._
import cats.effect.ExitCase._

import cats.implicits._
import cats.derived._
import java.util.concurrent.Executors

import shapeless._

import fetch._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.io.{BufferedReader, InputStreamReader, BufferedWriter, PrintWriter}
import java.nio.channels.AsynchronousChannelGroup
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}

object EventConsumer extends LogSupport {
  import Configuration.Implicits._

  def createListeningSocket =
    new ServerSocket(
      Configuration.sourcePort,
      1,
      InetAddress.getByName(Configuration.listeningAddress)
    )

  def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
    Sync[F]
      .delay(socket.close())
      .handleErrorWith(_ => Sync[F].delay(log.warn("dropped connection")))

  def processEvent[F[_]: ConcurrentEffect: ContextShift]: (Payload => F[Unit]) =
    (p: Payload) => {
      import encoderImplicits._
      import decoderImplicits._
      import dtoImplicits._

      ContextShift[F].shift *> (for {
        event <- EventType.fromP(p)
        _ <- event match {
              case Some(e) =>
                log.info(s"received ${e.toString}")
                // The cache could be used generically,
                // or the key can be used as a schema name
                MemoryEventCache.insertU("ALL_EVENTS", e)
              case _ => Sync[F].unit
            }
      } yield ())
    }

  def run[F[_]: ConcurrentEffect: ContextShift: Timer]: F[ExitCode] = {
    val l = implicitly[LiftIO[F]]

    for {
      e <- l.liftIO(IO(createListeningSocket))
            .bracket { s =>
              SubscriptionStream
                .subscribe[F](s, processEvent) >> l.liftIO(IO.pure(ExitCode.Success))
            } { s =>
              close[F](s) >> l.liftIO(IO(log.info("exiting")))
            }
    } yield e
  }
}
