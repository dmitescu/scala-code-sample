package com.github.dmitescu.styx.errors

import cats.data._

import scala.concurrent.Future
import scala.util.{Try, Success, Failure}

final case class EncodingException(message: String = "encoding error") extends Exception(message)
final case class DecodingException(message: String = "decoding error") extends Exception(message)

final case class UnboxingException(message: String = "unboxing error") extends Exception(message)

final case class EmptyEventException(message: String = "the event's payload is empty")
    extends Exception(message)

final case class ConnectionError(message: String = "connection error") extends Exception(message)

final case class ControllerError(message: String = "controller error") extends Exception(message)
final case class ClientControllerError(message: String = "client controller error")
    extends Exception(message)

package object output {
  type Output[A]        = Either[A, Throwable]
  type OutputT[F[_], A] = EitherT[F, A, Throwable]

  implicit def fromTry[A](b: Try[A]): Output[A] = b match {
    case Success(r) => Left(r)
    case Failure(t) => Right(t)
  }

  implicit def fromBlock[A](b: => A): Output[A] = Try(b)
}
