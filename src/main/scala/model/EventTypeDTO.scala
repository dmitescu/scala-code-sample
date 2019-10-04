package com.github.dmitescu.styx.model

import shapeless._
import shapeless.ops.product._

import scala.Enumeration
import scala.reflect.runtime.universe._

object EventTypeDTO extends Enumeration {
  type EventTypeDTO = Value

  val EventFollowType       = Value("F")
  val EventUnfollowType     = Value("U")
  val EventBroadcastType    = Value("B")
  val EventPrivateMsgType   = Value("P")
  val EventStatusUpdateType = Value("S")

  def isEventType(p: Payload) = values.exists(_.toString == p.payload)
}

package object dtoImplicits {
  import com.github.dmitescu.styx.errors._

  implicit val EventTypeDTOEncoder: Encoder[EventTypeDTO.EventTypeDTO] =
    Encoder.createEncoder(et => Payload(et.toString()))
  implicit val EventTypeDTODecoder: Decoder[EventTypeDTO.EventTypeDTO] =
    Decoder.createDecoder(
      dt =>
        EventTypeDTO.isEventType(dt) match {
          case true  => EventTypeDTO.values.filter(_.toString == dt.payload).head
          case false => throw new DecodingException
        }
    )
}

object EventDTO {
  import EventTypeDTO._
  import scala.language.higherKinds

  type EventDTOT[+T <: HList] = Int :: EventTypeDTO :: T

  object from extends Poly1 {
    implicit def caseAll = at[EventType] {
      case e @ EventFollow(id, from, to)         => id :: EventFollowType :: from :: to :: HNil
      case e @ EventUnfollow(id, from, to)       => id :: EventUnfollowType :: from :: to :: HNil
      case e @ EventBroadcast(id)                => id :: EventBroadcastType :: HNil
      case e @ EventPrivateMessage(id, from, to) => id :: EventPrivateMsgType :: from :: to :: HNil
      case e @ EventStatusUpdate(id, user)       => id :: EventStatusUpdateType :: user :: HNil
    }

    implicit def caseFollow = at[EventFollow] {
      case EventFollow(id, from, to) => id :: EventFollowType :: from :: to :: HNil
    }

    implicit def caseUnfollow = at[EventUnfollow] {
      case EventUnfollow(id, from, to) => id :: EventUnfollowType :: from :: to :: HNil
    }

    implicit def caseBroadcast = at[EventBroadcast] {
      case EventBroadcast(id) => id :: EventBroadcastType :: HNil
    }

    implicit def casePrivateMessage = at[EventPrivateMessage] {
      case EventPrivateMessage(id, from, to) => id :: EventPrivateMsgType :: from :: to :: HNil
    }

    implicit def caseStatusUpdate = at[EventStatusUpdate] {
      case EventStatusUpdate(id, user) => id :: EventStatusUpdateType :: user :: HNil
    }
  }
}
