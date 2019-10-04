package com.github.dmitescu.styx.model

import com.github.dmitescu.styx.errors._

import shapeless._

import cats._
import cats.implicits._
import cats.derived._

trait Decoder[T] {
  def decode(a: Payload): T
}

package object decoderImplicits {
  implicit class Decodable(p: Payload) {
    def decode[T: Decoder] =
      implicitly[Decoder[T]].decode(p)
  }
}

object Decoder {
  def createDecoder[T](ef: Payload => T): Decoder[T] =
    new Decoder[T] {
      def decode(v: Payload): T = ef(v)
    }

  implicit val stringDecoder: Decoder[String] =
    createDecoder { case Payload(p) => p }

  implicit val intDecoder: Decoder[Int] =
    createDecoder {
      case Payload(p) =>
        try {
          p.toInt
        } catch {
          case t: java.lang.NumberFormatException => throw new DecodingException(p)
        }
    }

  implicit val hnilDecoder: Decoder[HNil] =
    createDecoder {
      case Payload("") => HNil
      case _           => throw new DecodingException("garbage")
    }

  implicit def hlistDecoder[H: Decoder, T <: HList: Decoder]: Decoder[H :: T] =
    createDecoder {
      case Payload(p) =>
        p.contains('|') match {
          case false =>
            implicitly[Decoder[H]].decode(Payload(p)) ::
              implicitly[Decoder[T]].decode(Payload(""))
          case true =>
            implicitly[Decoder[H]].decode(Payload(p.substring(0, p.indexOf('|') match {
              case -1 => p.size
              case i  => i
            }))) ::
              implicitly[Decoder[T]].decode(Payload(p.substring(p.indexOf('|') match {
                case -1 => p.size
                case i  => i + 1
              }, p.size)))
        }
    }

  //A solution with Lazy types or/and reflection might work to be able to just try
  //to decode any type of HList on the spot
}
