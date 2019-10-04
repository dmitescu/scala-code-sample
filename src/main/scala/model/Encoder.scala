package com.github.dmitescu.styx.model

import com.github.dmitescu.styx.errors._

import shapeless.{HList, ::, HNil}

case class Payload(payload: String)

trait Encoder[T] {
  def encode(a: T): Payload
}

package object encoderImplicits {
  implicit class Encodable[T: Encoder](t: T) {
    def encode = implicitly[Encoder[T]].encode(t)
  }
}

object Encoder {
  def createEncoder[T](ef: T => Payload): Encoder[T] =
    new Encoder[T] {
      def encode(v: T): Payload = ef(v)
    }

  implicit val stringEncoder: Encoder[String] =
    createEncoder(s => new Payload(s))

  implicit val intEncoder: Encoder[Int] =
    createEncoder(i => Payload(i.toString))

  implicit val hnilEncoder: Encoder[HNil] =
    createEncoder(_ => throw new EncodingException)

  implicit def hlistEncoder[H: Encoder, T <: HList: Encoder]: Encoder[H :: T] =
    createEncoder {
      case h :: HNil => implicitly[Encoder[H]].encode(h)
      case h :: t =>
        Payload(
          implicitly[Encoder[H]].encode(h).payload ++ "|" ++
            implicitly[Encoder[T]].encode(t).payload
        )
    }
}
