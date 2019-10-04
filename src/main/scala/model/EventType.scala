package com.github.dmitescu.styx.model

import com.github.dmitescu.styx.LogSupport
import com.github.dmitescu.styx.errors._
import EventTypeDTO._
import EventDTO.EventDTOT
import scala.math.Ordering

import cats._
import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._

import cats.implicits._
import cats.derived._

import shapeless._

import scala.util.{Try, Success, Failure}

abstract class EventType
case class EventFollow(id: Int, fromUserId: Int, toUserId: Int)         extends EventType
case class EventUnfollow(id: Int, fromUserId: Int, toUserId: Int)       extends EventType
case class EventBroadcast(id: Int)                                      extends EventType
case class EventPrivateMessage(id: Int, fromUserId: Int, toUserId: Int) extends EventType
case class EventStatusUpdate(id: Int, userId: Int)                      extends EventType

package object eventTypeImplicits {
  implicit val EventTypeOrdering: Ordering[EventType] = new Ordering[EventType] {
    override def compare(e1: EventType, e2: EventType): Int =
      EventDTO.from(e1).head.compare(EventDTO.from(e2).head)
  }
}

object EventType extends LogSupport {
  @throws(classOf[UnboxingException])
  def from(e: EventDTO.EventDTOT[_]): EventType = e match {
    case id :: EventFollowType :: from :: to :: HNil =>
      EventFollow(id, from.asInstanceOf[Int], to.asInstanceOf[Int])
    case id :: EventUnfollowType :: from :: to :: HNil =>
      EventUnfollow(id, from.asInstanceOf[Int], to.asInstanceOf[Int])
    case id :: EventBroadcastType :: HNil => EventBroadcast(id.asInstanceOf[Int])
    case id :: EventPrivateMsgType :: from :: to :: HNil =>
      EventPrivateMessage(id, from.asInstanceOf[Int], to.asInstanceOf[Int])
    case id :: EventStatusUpdateType :: user :: HNil =>
      EventStatusUpdate(id, user.asInstanceOf[Int])
    case _ =>
      throw new UnboxingException
  }

  // Wrapper to try out all event types;
  // Three ideas which could be tried which might remove the need for this:
  //   1. Recursively defining a lazy type
  //   2. letting the type inference system figure out the type by using _
  //      on all Case.Aux cases, and implicitly building them
  //   3. Projections?

  def fromP[F[_]: Sync](p: Payload): F[Option[EventType]] = {
    import decoderImplicits._
    import dtoImplicits._

    def decodeAction[T <: HList: Decoder] =
      Try { from(p.decode[EventDTOT[T]]) }

    def event() =
      Seq(decodeAction[Int :: Int :: HNil], decodeAction[Int :: HNil], decodeAction[HNil])
        .map(_.toEither)

    Sync[F].delay {
      val events = event

      val candidate = events.filter(_.isRight).map(_.right.get).headOption
      val error     = events.filter(_.isLeft).map(_.left.get).headOption

      candidate.isEmpty match {
        case true =>
          log.warn(error.head.getMessage)
          None
        case false => candidate
      }
    }
  }

  object fromF extends Poly1 {
    implicit def caseno = at[Int :: EventTypeDTO :: HNil] {
      case id :: EventBroadcastType :: HNil => EventBroadcast(id)
      case _ =>
        throw new UnboxingException
    }

    implicit def case1int = at[Int :: EventTypeDTO :: Int :: HNil] {
      case id :: EventStatusUpdateType :: user :: HNil =>
        EventStatusUpdate(id, user.asInstanceOf[Int])
      case _ =>
        throw new UnboxingException
    }

    implicit def case2int = at[Int :: EventTypeDTO :: Int :: Int :: HNil] {
      case id :: EventFollowType :: from :: to :: HNil =>
        EventFollow(id, from.asInstanceOf[Int], to.asInstanceOf[Int])
      case id :: EventUnfollowType :: from :: to :: HNil =>
        EventUnfollow(id, from.asInstanceOf[Int], to.asInstanceOf[Int])
      case id :: EventPrivateMsgType :: from :: to :: HNil =>
        EventPrivateMessage(id, from.asInstanceOf[Int], to.asInstanceOf[Int])
      case _ =>
        throw new UnboxingException
    }
  }
}
