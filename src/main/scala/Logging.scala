package com.github.dmitescu.styx

import org.log4s._

trait LogSupport {
  self =>
  lazy val log = getLogger(this.getClass.getSimpleName)
}
