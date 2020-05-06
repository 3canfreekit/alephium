package org.alephium.appserver

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import org.scalatest.concurrent.ScalaFutures

import org.alephium.flow.platform.PlatformConfig
import org.alephium.util.AlephiumSpec

class ServerSpec extends AlephiumSpec with ScalaFutures {

  behavior of "Server"
  it should "start and stop correctly" in {

    implicit val config: PlatformConfig             = PlatformConfig.loadDefault()
    implicit val system: ActorSystem                = ActorSystem("Root", config.all)
    implicit val executionContext: ExecutionContext = system.dispatcher

    val server = new ServerImpl

    server.start.futureValue is (())
    server.stop.futureValue is (())
  }
}
