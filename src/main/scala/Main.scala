package com.github.dmitescu.styx

import java.util.concurrent.Executors
import model._
import event._

import cats.data._
import cats.effect._
import cats.implicits._

import shapeless._

object Main extends IOApp with LogSupport {
  def run(args: List[String]): IO[ExitCode] = {
    import encoderImplicits._
    import decoderImplicits._
    import dtoImplicits._

    import Configuration.Implicits._

    for {
      _  <- IO(log.info("starting event processing")).as(ExitCode.Success)
      f1 <- EventConsumer.run[IO].start
      f2 <- EventProcessor.run[IO].start

      c1 <- f1.join
      c2 <- f2.join
    } yield Seq(c1, c2).find(_ == ExitCode.Error).getOrElse(ExitCode.Success)
  }
}
