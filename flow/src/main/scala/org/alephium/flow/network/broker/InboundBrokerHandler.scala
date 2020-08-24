package org.alephium.flow.network.broker

import akka.io.Tcp

import org.alephium.flow.network.CliqueManager
import org.alephium.flow.setting.NetworkSetting
import org.alephium.protocol.message.{Hello, Payload}
import org.alephium.protocol.model.CliqueInfo
import org.alephium.util.{ActorRefT, Duration}

trait InboundBrokerHandler extends BrokerHandler {
  def selfCliqueInfo: CliqueInfo

  def networkSetting: NetworkSetting

  def connection: ActorRefT[Tcp.Command]

  def cliqueManager: ActorRefT[CliqueManager.Command]

  override def handShakeDuration: Duration = networkSetting.retryTimeout

  override val brokerConnectionHandler: ActorRefT[BrokerConnectionHandler.Command] =
    context.actorOf(BrokerConnectionHandler.clique(connection))

  override def handShakeMessage: Payload =
    Hello.unsafe(selfCliqueInfo.id, selfCliqueInfo.selfBrokerInfo)

  override def pingFrequency: Duration = networkSetting.pingFrequency
}
