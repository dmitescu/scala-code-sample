package com.github.dmitescu.styx.model

import org.specs2.shapeless.Projection._
import org.specs2.specification._
import org.specs2.mutable.Specification
import org.specs2.mock.Mockito

import shapeless.{HList, ::, HNil}

import scala.language.higherKinds

class DecoderSpec extends Specification with Mockito {
  import decoderImplicits._

  "Decoder should" >> {
    "decode nothing" >> {
      emptyPayload.decode[HNil] must_== HNil
    }
    "decode int" >> {
      intPayload.decode[Int] must_== 1
    }

    "decode string" >> {
      stringPayload.decode[String] must_== "X"
    }

    "decode combinations" >> {
      multiPayload.decode[Int :: String :: HNil] must_== 1 :: "B" :: HNil
    }

    "decode EventTypeDTO" >> {
      import dtoImplicits._
      typeDtoPayload.decode[EventTypeDTO.EventTypeDTO] must_== EventTypeDTO.EventFollowType
    }
  }

  def emptyPayload   = Payload("")
  def intPayload     = Payload("1")
  def stringPayload  = Payload("X")
  def multiPayload   = Payload("1|B")
  def typeDtoPayload = Payload("F")
}
