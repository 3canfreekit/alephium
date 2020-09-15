package org.alephium.flow.network

import java.net.InetSocketAddress

import akka.actor.Props
import akka.event.LoggingAdapter
import akka.io.Tcp

import org.alephium.flow.core.BlockFlow
import org.alephium.flow.handler.AllHandlers
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.network.interclique.{InboundBrokerHandler, OutboundBrokerHandler}
import org.alephium.flow.network.sync.BlockFlowSynchronizer
import org.alephium.flow.setting.{DiscoverySetting, NetworkSetting}
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{BrokerInfo, ChainIndex, CliqueInfo, InterCliqueInfo, PeerId}
import org.alephium.util.{ActorRefT, BaseActor, Duration}

object InterCliqueManager {
  // scalastyle:off parameter.number
  def props(selfCliqueInfo: CliqueInfo,
            blockflow: BlockFlow,
            allHandlers: AllHandlers,
            discoveryServer: ActorRefT[DiscoveryServer.Command],
            blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
      implicit brokerConfig: BrokerConfig,
      networkSetting: NetworkSetting,
      discoverySetting: DiscoverySetting): Props =
    Props(
      new InterCliqueManager(selfCliqueInfo,
                             blockflow,
                             allHandlers,
                             discoveryServer,
                             blockFlowSynchronizer))
  //scalastyle:on

  sealed trait Command              extends CliqueManager.Command
  final case object GetSyncStatuses extends Command

  final case class SyncStatus(peerId: PeerId, address: InetSocketAddress, isSynced: Boolean)

  final case class BrokerState(info: BrokerInfo,
                               actor: ActorRefT[BrokerHandler.Command],
                               isSynced: Boolean) {
    def setSynced(): BrokerState = BrokerState(info, actor, isSynced = true)

    def readyFor(chainIndex: ChainIndex): Boolean = {
      isSynced && info.contains(chainIndex.from)
    }
  }
}

class InterCliqueManager(selfCliqueInfo: CliqueInfo,
                         blockflow: BlockFlow,
                         allHandlers: AllHandlers,
                         discoveryServer: ActorRefT[DiscoveryServer.Command],
                         blockFlowSynchronizer: ActorRefT[BlockFlowSynchronizer.Command])(
    implicit brokerConfig: BrokerConfig,
    networkSetting: NetworkSetting,
    discoverySetting: DiscoverySetting)
    extends BaseActor
    with InterCliqueManagerState {
  import InterCliqueManager._
  discoveryServer ! DiscoveryServer.GetNeighborCliques

  override def receive: Receive = handleMessage orElse handleConnection orElse awaitNeighborCliques

  def awaitNeighborCliques: Receive = {
    case DiscoveryServer.NeighborCliques(neighborCliques) =>
      if (neighborCliques.nonEmpty) {
        log.debug(s"Got ${neighborCliques.length} from discovery server")
        neighborCliques.foreach(connect)
      } else {
        // TODO: refine the condition, check the number of brokers for example
        log.debug(s"Try to fetch peer brokers from discovery server")
        if (discoverySetting.bootstrap.nonEmpty) {
          scheduleOnce(discoveryServer.ref,
                       DiscoveryServer.GetNeighborCliques,
                       Duration.ofSecondsUnsafe(2))
          ()
        }
      }
  }

  def handleConnection: Receive = {
    case Tcp.Connected(remoteAddress, _) =>
      log.debug(s"Connected to ${remoteAddress}")
      val name = BaseActor.envalidActorName(s"InboundBrokerHandler-${remoteAddress}")
      val props =
        InboundBrokerHandler.props(
          selfCliqueInfo,
          remoteAddress,
          ActorRefT[Tcp.Command](sender()),
          blockflow,
          allHandlers,
          ActorRefT[CliqueManager.Command](self),
          blockFlowSynchronizer
        )
      context.actorOf(props, name)
      ()
    case CliqueManager.HandShaked(brokerInfo) =>
      log.debug(s"Start syncing with inter-clique node: $brokerInfo")
      if (brokerConfig.intersect(brokerInfo)) {
        addBroker(brokerInfo, sender())
      } else {
        context stop sender()
      }
    case CliqueManager.Synced(brokerInfo) =>
      log.debug(s"No new blocks from $brokerInfo")
      setSynced(brokerInfo)
  }

  def handleMessage: Receive = {
    case message: CliqueManager.BroadCastBlock =>
      val block = message.block
      log.debug(s"Broadcasting block ${block.shortHex} for ${block.chainIndex}")
      iterBrokers { (peerId, brokerState) =>
        if (!message.origin.isFrom(peerId.cliqueId) && brokerState.readyFor(block.chainIndex)) {
          log.debug(s"Send block to broker $peerId")
          brokerState.actor ! BrokerHandler.Send(message.blockMsg)
        }
      }
    case message: CliqueManager.BroadCastTx =>
      log.debug(s"Broadcasting tx ${message.tx.shortHex} for ${message.chainIndex}")
      iterBrokers { (peerId, brokerState) =>
        if (!message.origin.isFrom(peerId.cliqueId) && brokerState.readyFor(message.chainIndex)) {
          log.debug(s"Send tx to broker $peerId")
          brokerState.actor ! BrokerHandler.Send(message.txMsg)
        }
      }

    case GetSyncStatuses =>
      val syncStatuses: Seq[SyncStatus] = mapBrokers { (peerId, brokerState) =>
        SyncStatus(peerId, brokerState.info.address, brokerState.isSynced)
      }
      sender() ! syncStatuses
  }

  def connect(clique: InterCliqueInfo): Unit = {
    clique.brokers.foreach { brokerInfo =>
      if (brokerConfig.intersect(brokerInfo) && !containsBroker(brokerInfo)) connect(brokerInfo)
    }
  }

  private def connect(brokerInfo: BrokerInfo): Unit = {
    log.debug(s"Try to connect to $brokerInfo")
    val name = BaseActor.envalidActorName(s"OutboundBrokerHandler-$brokerInfo")
    val props =
      OutboundBrokerHandler.props(selfCliqueInfo,
                                  brokerInfo,
                                  blockflow,
                                  allHandlers,
                                  self,
                                  blockFlowSynchronizer)
    context.actorOf(props, name)
    ()
  }
}

trait InterCliqueManagerState {
  import InterCliqueManager._

  def log: LoggingAdapter

  // The key is (CliqueId, BrokerId)
  private val brokers = collection.mutable.HashMap.empty[PeerId, BrokerState]

  def addBroker(brokerInfo: BrokerInfo, broker: ActorRefT[BrokerHandler.Command]): Unit = {
    val peerId = brokerInfo.peerId
    if (!brokers.contains(peerId)) {
      brokers += peerId -> BrokerState(brokerInfo, broker, isSynced = false)
    } else {
      log.warning(s"Ignore another connection from $peerId")
    }
  }

  def containsBroker(brokerInfo: BrokerInfo): Boolean = {
    brokers.contains(brokerInfo.peerId)
  }

  def iterBrokers(f: (PeerId, BrokerState) => Unit): Unit = {
    brokers.foreach {
      case (peerId, state) => f(peerId, state)
    }
  }

  def mapBrokers[A](f: (PeerId, BrokerState) => A): Seq[A] = {
    brokers.collect {
      case (peerId, state) => f(peerId, state)
    }.toSeq
  }

  def setSynced(brokerInfo: BrokerInfo): Unit = {
    val peerId = brokerInfo.peerId
    brokers.get(peerId) match {
      case Some(state) => brokers(peerId) = state.setSynced()
      case None        => log.warning(s"Unexpected message Synced from $peerId")
    }
  }
}
