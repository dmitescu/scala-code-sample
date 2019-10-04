package com.github.dmitescu.styx.event

import com.github.dmitescu.styx.LogSupport
import com.github.dmitescu.styx.model._
import com.github.dmitescu.styx.errors._
import scala.concurrent.{Channel, Future, SyncVar}
import java.io.{BufferedReader, BufferedWriter, PrintWriter, InputStreamReader}
import java.net.{ServerSocket, Socket}

import cats._
// import cats.data._
import cats.effect._
import cats.effect.concurrent.{Ref, MVar}
import cats.effect.ExitCase._
import cats.effect.syntax.all._
import cats.implicits._
import cats.derived._
import scala.concurrent.ExecutionContext

/*
 * The publishing stream is where the connection protocol is being decided,
 * and where the pool is stored and accessed
 */
object PublisherStream extends LogSupport {
  def clientProtocol[F[_]: ConcurrentEffect: ContextShift](
      socket: Socket,
      registry: Ref[F, Map[Int, Resource[F, BufferedWriter]]],
      topicReg: SyncVar[Map[Int, MVar[F, Payload]]]
  )(implicit ec: ExecutionContext): F[Unit] = {

    /*
     * Registers client by reading the first line and leaving the reader open
     */
    def registerClient(clientSocket: Socket, clientId: Int): F[Resource[F, BufferedWriter]] =
      for {
        _                <- Sync[F].delay(log.info("attempting to register client"))
        socketResource   <- Sync[F].delay(writer(socket))
        alreadyConnected <- registry.get.map(_.get(clientId).fold(false)(_ => true))
        isSuccess <- registry.modify[Boolean] { reg =>
                      alreadyConnected match {
                        case false =>
                          (
                            reg ++ Map[Int, Resource[F, BufferedWriter]](
                              clientId -> socketResource
                            ),
                            true
                          )
                        case _ => (reg, false)
                      }
                    }
        cm     <- Sync[F].delay(topicReg.get.asInstanceOf[Map[Int, MVar[F, Payload]]])
        _      <- Sync[F].delay(topicReg.unset)
        newVar <- MVar[F].empty[Payload]
        nm <- Sync[F].delay(isSuccess match {
               case true  => cm ++ Map[Int, MVar[F, Payload]](clientId -> newVar)
               case false => cm
             })
        _ <- Sync[F].delay(topicReg.put(nm))
      } yield {
        if (!isSuccess) {
          throw new ClientControllerError("client already registered/cache affected")
        } else {
          log.info(s"${clientId} registed succesfully")
        }
        socketResource
      }

    /*
     *  Main loop-point
     */
    def loop(writer: BufferedWriter, clientId: Int): F[Unit] =
      for {
        topicM  <- Sync[F].delay(topicReg.get)
        topic   <- Sync[F].delay(topicM.get(clientId).get)
        payload <- topic.take
        _       <- Sync[F].delay(log.info(s"sending ${payload.payload} to ${clientId}"))
        _       <- Sync[F].delay(writer.write(payload.payload))
        _       <- Sync[F].delay(writer.newLine())
        _       <- Sync[F].delay(writer.flush())
        _       <- ContextShift[F].shift *> loop(writer, clientId)
      } yield ()

    def reader(clientSocket: Socket): Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay(new BufferedReader(new InputStreamReader(clientSocket.getInputStream())))
      } { _ =>
        Sync[F].delay(Thread.sleep(100))
      }

    def writer(clientSocket: Socket): Resource[F, BufferedWriter] =
      Resource.make {
        Sync[F].delay(new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())))
      } { writer =>
        Sync[F].delay(writer.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def read(reader: BufferedReader): F[Int] =
      for {
        line <- Sync[F].delay(reader.readLine().trim)
        _    <- Sync[F].delay(log.info("new client with id " + line))
        _    <- registerClient(socket, line.toInt)
      } yield (line.toInt)

    for {
      clientId <- reader(socket).use { reader =>
                   read(reader).handleError { t =>
                     Sync[F].delay(log.warn(t.toString))
                     throw new ClientControllerError(t.toString)
                   }
                 }
      _ <- writer(socket).use { writer =>
            ContextShift[F].shift *> (loop(writer, clientId).handleErrorWith { t =>
              Sync[F].delay(log.warn(t.toString))
            })
          }
    } yield ()
  }

  /*
   * Recursively accepts connections by forking into a fiber and joining
   */
  def subscribe[F[_]: ConcurrentEffect: ContextShift](
      serverSocket: ServerSocket,
      topicReg: SyncVar[Map[Int, MVar[F, Payload]]],
      registry: Ref[F, Map[Int, Resource[F, BufferedWriter]]]
  )(implicit ec: ExecutionContext): F[Unit] = {

    def close(socket: Socket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    for {
      f <- Sync[F]
            .delay(serverSocket.accept())
            .bracketCase { c =>
              clientProtocol[F](c, registry, topicReg).guaranteeCase {
                case e @ (Error(_) | Canceled) =>
                  log.warn(e.toString)
                  close(c)
                case _ => Sync[F].unit
              }.start
            } { (socket, exit) =>
              exit match {
                case Completed                       => Sync[F].unit
                case Error(ClientControllerError(_)) => Sync[F].unit
                case Error(_) | Canceled             => close(socket)
              }
            }
      fm <- ContextShift[F].shift *> subscribe(serverSocket, topicReg, registry).start
      _  <- f.join
      _  <- fm.join
    } yield ()
  }
}
