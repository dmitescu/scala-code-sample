package com.github.dmitescu.styx.model

import org.specs2.shapeless.Projection._
import org.specs2.specification._
import org.specs2.mutable.Specification
import org.specs2.mock.Mockito

import shapeless.{HList, ::, HNil}

import scala.language.higherKinds

class EncoderSpec extends Specification with Mockito {
  import encoderImplicits._

  "Encoder should" >> {
    "encode int" >> {
      intPayload.encode.projectOn[Payload] must_== Payload(intPayload.toString)
    }

    "encode string" >> {
      stringPayload.encode.projectOn[Payload] must_== Payload(stringPayload)
    }

    "encode HList combinations" >> {
      multiPayload.encode.projectOn[Payload] must_== Payload("1|B")
    }

    "encode EventTypeDTOs" >> {
      import dtoImplicits._

      typeDtoPayload.encode.projectOn[Payload] must_== Payload("F")
    }
  }

  def intPayload     = 1
  def stringPayload  = "X"
  def multiPayload   = 1 :: "B" :: HNil
  def typeDtoPayload = EventTypeDTO.EventFollowType
}
