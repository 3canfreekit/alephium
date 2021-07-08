// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.app

import scala.concurrent.ExecutionContext

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.TestProbe
import akka.util.Timeout
import io.vertx.core.Vertx
import org.scalatest.{Assertion, EitherValues}
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import sttp.tapir.server.vertx.VertxFutureServerInterpreter._

import org.alephium.flow.handler.FlowHandler.BlockNotify
import org.alephium.flow.handler.TestUtils
import org.alephium.json.Json._
import org.alephium.protocol.{BlockHash, Hash}
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util._

class WebSocketServerSpec
    extends AlephiumSpec
    with NoIndexModelGenerators
    with EitherValues
    with ScalaFutures
    with Eventually
    with NumericHelpers
    with IntegrationPatience {
  import ServerFixture._

  behavior of "http"

  it should "encode BlockNotify" in new ServerFixture {
    val dep  = BlockHash.hash("foo")
    val deps = AVector.fill(groupConfig.depsNum)(dep)
    val header =
      BlockHeader.unsafeWithRawDeps(
        deps,
        Hash.zero,
        Hash.hash("bar"),
        TimeStamp.zero,
        Target.Max,
        Nonce.zero
      )

    val block       = Block(header, AVector.empty)
    val blockNotify = BlockNotify(block, 1)
    val headerHash  = header.hash.toHexString
    val chainIndex  = header.chainIndex

    val result = WebSocketServer.blockNotifyEncode(blockNotify, networkType)

    val depsString = AVector.fill(groupConfig.depsNum)(s""""${dep.toHexString}"""").mkString(",")
    show(
      result
    ) is s"""{"hash":"$headerHash","timestamp":0,"chainFrom":${chainIndex.from.value},"chainTo":${chainIndex.to.value},"height":1,"deps":[$depsString],"transactions":[]}"""
  }

  behavior of "ws"

  it should "receive multiple events" in new RouteWS {
    checkWS {
      (0 to 3).foreach { _ => sendEventAndCheck }
    }
  }

  trait WebSocketServerFixture extends ServerFixture {

    implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
    implicit val system: ActorSystem                = ActorSystem("websocket-server-spec")
    lazy val blockFlowProbe                         = TestProbe()
    val (allHandlers, _)                            = TestUtils.createAllHandlersProbe
    lazy val node = new NodeDummy(
      dummyIntraCliqueInfo,
      dummyNeighborPeers,
      dummyBlock,
      blockFlowProbe.ref,
      allHandlers,
      dummyTx,
      storages
    )
    lazy val server: WebSocketServer = WebSocketServer(node)
  }

  trait RouteWS extends WebSocketServerFixture {

    private val vertx      = Vertx.vertx()
    private val httpClient = vertx.createHttpClient()
    val port               = node.config.network.wsPort
    val blockNotifyProbe   = TestProbe()

    val blockNotify = BlockNotify(blockGen.sample.get, height = 0)
    def sendEventAndCheck: Assertion = {
      node.eventBus ! blockNotify

      blockNotifyProbe.expectMsgPF() { case message: String =>
        val notification = read[NotificationUnsafe](message).asNotification.toOption.get
        notification.method is "block_notify"
      }
    }

    def checkWS[A](f: => A) = {
      server.start().futureValue

      implicit val timeout: Timeout = Timeout(Duration.ofSecondsUnsafe(5).asScala)
      eventually {
        node.eventBus
          .ask(EventBus.ListSubscribers)
          .mapTo[EventBus.Subscribers]
          .futureValue
          .value
          .contains(server.eventHandler) is true
      }

      val ws = httpClient
        .webSocket(port, "127.0.0.1", "/events")
        .asScala
        .map { ws =>
          ws.textMessageHandler { blockNotify =>
            blockNotifyProbe.ref ! blockNotify
          }
          ws
        }
        .futureValue

      eventually {
        server.eventHandler
          .ask(WebSocketServer.EventHandler.ListSubscribers)
          .mapTo[AVector[String]]
          .futureValue
          .nonEmpty is true
      }

      f

      ws.close().asScala.futureValue
      server.stop().futureValue
    }
  }
}
