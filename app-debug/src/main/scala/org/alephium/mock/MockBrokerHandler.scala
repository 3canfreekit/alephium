package org.alephium.mock

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props}
import com.codahale.metrics.{Histogram, MetricRegistry}
import org.alephium.flow.PlatformConfig
import org.alephium.flow.network.clique.{BrokerHandler, InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.storage.AllHandlers
import org.alephium.monitoring.Monitoring
import org.alephium.protocol.model.CliqueInfo

object MockBrokerHandler {
  trait Builder extends BrokerHandler.Builder {
    override def createInboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        connection: ActorRef,
        blockHandlers: AllHandlers)(implicit config: PlatformConfig): Props =
      Props(new MockInboundBrokerHandler(selfCliqueInfo, connection, blockHandlers))

    override def createOutboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        brokerId: Int,
        remote: InetSocketAddress,
        blockHandlers: AllHandlers)(implicit config: PlatformConfig): Props =
      Props(new MockOutboundBrokerHandler(selfCliqueInfo, brokerId, remote, blockHandlers))

  }
}

class MockInboundBrokerHandler(selfCliqueInfo: CliqueInfo,
                               connection: ActorRef,
                               allHandlers: AllHandlers)(implicit config: PlatformConfig)
    extends InboundBrokerHandler(selfCliqueInfo, connection, allHandlers) {
  val delays: Histogram =
    Monitoring.metrics.histogram(MetricRegistry.name(remote.toString, "delay"))

  override def handlePing(nonce: Int, delay: Long): Unit = {
    super.handlePing(nonce, delay)
    delays.update(delay)
  }
}

class MockOutboundBrokerHandler(selfCliqueInfo: CliqueInfo,
                                brokerId: Int,
                                remote: InetSocketAddress,
                                allHandlers: AllHandlers)(implicit config: PlatformConfig)
    extends OutboundBrokerHandler(selfCliqueInfo, brokerId, remote, allHandlers) {
  val delays: Histogram =
    Monitoring.metrics.histogram(MetricRegistry.name(remote.toString, "delay"))

  override def handlePing(nonce: Int, delay: Long): Unit = {
    super.handlePing(nonce, delay)
    delays.update(delay)
  }
}
