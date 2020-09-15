package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.io.{IO, Tcp}
import akka.testkit.{SocketUtil, TestActorRef, TestProbe}
import org.scalatest.concurrent.Eventually.eventually

import org.alephium.flow.network.broker.BrokerManager
import org.alephium.util.AlephiumActorSpec

class TcpControllerSpec extends AlephiumActorSpec("TcpController") {
  trait Fixture {
    val brokerManager = TestProbe()
    val bootstrapper  = TestProbe()

    val bindAddress = SocketUtil.temporaryServerAddress()
    val controller =
      TestActorRef[TcpController](TcpController.props(bindAddress, brokerManager.ref))
    val controllerActor = controller.underlyingActor

    controller ! TcpController.Start(bootstrapper.ref)

    def connectToController(): (InetSocketAddress, ActorRef) = {
      IO(Tcp) ! Tcp.Connect(bindAddress)
      val confirm = brokerManager.expectMsgType[BrokerManager.ConfirmConnection]
      controller ! TcpController.ConnectionConfirmed(confirm.connected, confirm.connection)

      bootstrapper.expectMsgType[Tcp.Connected]
      val connection = bootstrapper.lastSender
      (confirm.connected.remoteAddress, connection)
    }
  }

  it should "bind a tcp socket and accept incoming connections" in new Fixture {
    val address0 = connectToController()._1
    controllerActor.confirmedConnections.contains(address0) is true

    val address1 = connectToController()._1
    controllerActor.confirmedConnections.contains(address1) is true

    val address2 = connectToController()._1
    controllerActor.confirmedConnections.contains(address2) is true
  }

  it should "not accept denied connections" in new Fixture {
    IO(Tcp) ! Tcp.Connect(bindAddress)
    val confirm = brokerManager.expectMsgType[BrokerManager.ConfirmConnection]
    controller ! TcpController.ConnectionDenied(confirm.connected, confirm.connection)

    val address = confirm.connected.remoteAddress
    controllerActor.confirmedConnections.contains(address) is false
  }

  it should "monitor the termination of connections" in new Fixture {
    val (address, connection) = connectToController()
    controllerActor.confirmedConnections.contains(address) is true

    system.stop(connection)
    eventually {
      controllerActor.confirmedConnections.contains(address) is false
    }
  }

  it should "handle outgoing connections" in {
    val fixture1 = new Fixture {}
    val fixture2 = new Fixture {}
    eventually {
      fixture2.controller ! TcpController.ConnectTo(fixture1.bindAddress)
      fixture2.controllerActor.confirmedConnections.contains(fixture1.bindAddress) is true
    }
  }
}
