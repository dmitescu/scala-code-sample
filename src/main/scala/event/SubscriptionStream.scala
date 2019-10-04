package com.github.dmitescu.styx.event

import java.io.{BufferedReader, InputStreamReader}
import java.net.{ServerSocket, Socket}

import cats._
import cats.effect._
import cats.effect.ExitCase._
import cats.effect.syntax.all._
import cats.implicits._
import com.github.dmitescu.styx.LogSupport
import com.github.dmitescu.styx.model._
import com.github.dmitescu.styx.errors._

import scala.concurrent.ExecutionContext

/*
 * Just reads payloads
 */
object SubscriptionStream extends LogSupport {
  def subscriptionProtocol[F[_]: ConcurrentEffect: ContextShift](
      socket: Socket,
      insertF: (Payload => F[Unit])
  )(implicit ec: ExecutionContext): F[Unit] = {

    def reader(clientSocket: Socket): Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay(new BufferedReader(new InputStreamReader(clientSocket.getInputStream())))
      } { reader =>
        Sync[F].delay(reader.close()).handleError(_ => Sync[F].unit)
      }

    def read(reader: BufferedReader): F[Unit] =
      for {
        line <- ContextShift[F].shift *> Sync[F].delay(reader.readLine())
        _ <- if (line == null) {
              Sync[F].raiseError(ControllerError("line cannot be null or EOF"))
            } else Sync[F].unit
        _ <- insertF(Payload(line))
        _ <- read(reader)
      } yield ()

    reader(socket).use { reader =>
      read(reader).handleErrorWith { t =>
        Sync[F].delay(log.warn(t.toString))
      }
    }
  }

  def subscribe[F[_]: ConcurrentEffect: ContextShift](
      serverSocket: ServerSocket,
      insertF: (Payload => F[Unit])
  )(implicit ec: ExecutionContext): F[Unit] = {

    def close(socket: Socket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    for {
      _ <- Sync[F]
            .delay(serverSocket.accept())
            .bracketCase { s =>
              subscriptionProtocol(s, insertF)
                .guarantee(close(s))
                .guaranteeCase {
                  case Canceled => Sync[F].delay(serverSocket.close())
                }
                .start
            } { (socket, exit) =>
              exit match {
                case Completed => Sync[F].unit
                case Error(e) =>
                  Sync[F].delay(log.warn(e.toString)) >> close(socket)
                case Canceled =>
                  close(socket)
              }
            }
      _ <- ContextShift[F].shift *> subscribe(serverSocket, insertF)
    } yield ()
  }
}
