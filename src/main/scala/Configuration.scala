package com.github.dmitescu.styx

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object Configuration {
  private val sourcePortEnv      = "V_SOURCE_PORT"
  private val clientsPortEnv     = "V_CLIENTS_PORT"
  private val listeningIfaceAddr = "V_ADDR"
  private val maxClientsEnv      = "V_MAX_CLIENTS"
  private val cacheSizeEnv       = "V_CACHE_SIZE"
  private val cleanupFactorEnv   = "V_CLEANUP_FACTOR"
  private val batchIntervalEnv   = "V_BATCH_INTERVAL"

  lazy val sourcePort: Int =
    sys.env.get(sourcePortEnv).map(_.toInt).getOrElse(9090)
  lazy val clientsPort: Int =
    sys.env.get(clientsPortEnv).map(_.toInt).getOrElse(9099)
  lazy val listeningAddress: String =
    sys.env.get(listeningIfaceAddr).getOrElse("0.0.0.0")
  lazy val maxClients: Int =
    sys.env.get(maxClientsEnv).map(_.toInt).getOrElse(100)

  lazy val batchInterval: Int =
    sys.env.get(batchIntervalEnv).map(_.toInt).getOrElse(100)
  lazy val cacheSize: Int =
    sys.env.get(cacheSizeEnv).map(_.toInt).getOrElse(500)
  lazy val cleanupFactor: Int =
    sys.env.get(cleanupFactorEnv).map(_.toInt).getOrElse(7)

  object Implicits {
    implicit val pool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3))
  }

  // def logLevel = sys.env.get(logLevelEnv).getOrElse("INFO")
}
