package org.alephium.flow.network.clique

import java.net.InetSocketAddress

import akka.actor.{ActorRef, Props, Timers}
import akka.io.Tcp
import akka.util.ByteString
import org.alephium.flow.PlatformConfig
import org.alephium.flow.model.DataOrigin.Remote
import org.alephium.flow.network.CliqueManager
import org.alephium.flow.storage.{AllHandlers, BlockChainHandler, FlowHandler, HeaderChainHandler}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.message._
import org.alephium.protocol.model.{BrokerInfo, CliqueInfo}
import org.alephium.serde.{SerdeError, SerdeResult}
import org.alephium.util.{AVector, BaseActor}

import scala.annotation.tailrec
import scala.util.Random

object BrokerHandler {
  object Timer

  sealed trait Command
  case object SendPing extends Command

  def envelope(payload: Payload): Tcp.Write =
    envelope(Message(payload))

  def envelope(message: Message): Tcp.Write =
    Tcp.Write(Message.serialize(message))

  def deserialize(data: ByteString)(
      implicit config: GroupConfig): SerdeResult[(AVector[Message], ByteString)] = {
    @tailrec
    def iter(rest: ByteString,
             acc: AVector[Message]): SerdeResult[(AVector[Message], ByteString)] = {
      Message._deserialize(rest) match {
        case Right((message, newRest)) =>
          iter(newRest, acc :+ message)
        case Left(_: SerdeError.NotEnoughBytes) =>
          Right((acc, rest))
        case Left(e) =>
          Left(e)
      }
    }
    iter(data, AVector.empty)
  }

  trait Builder {
    def createInboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        connection: ActorRef,
        blockHandlers: AllHandlers)(implicit config: PlatformConfig): Props =
      Props(new InboundBrokerHandler(selfCliqueInfo, connection, blockHandlers))

    def createOutboundBrokerHandler(
        selfCliqueInfo: CliqueInfo,
        remoteBroker: BrokerInfo,
        brokerId: Int,
        remote: InetSocketAddress,
        blockHandlers: AllHandlers)(implicit config: PlatformConfig): Props =
      Props(
        new OutboundBrokerHandler(selfCliqueInfo, remoteBroker, brokerId, remote, blockHandlers))
  }
}

trait BrokerHandler extends BaseActor with Timers {

  implicit def config: PlatformConfig

  def selfCliqueInfo: CliqueInfo
  def cliqueInfo: CliqueInfo
  def remoteBroker: BrokerInfo
  def remoteIndex: Int
  def remote: InetSocketAddress
  def connection: ActorRef
  def allHandlers: AllHandlers

  def handshakeOut(): Unit = {
    connection ! BrokerHandler.envelope(Hello(selfCliqueInfo, config.brokerInfo.id))
    context become handleWith(ByteString.empty, awaitHelloAck, handlePayload)
  }

  def handshakeIn(): Unit = {
    context become handleWith(ByteString.empty, awaitHello, handlePayload)
  }

  def afterHandShake(): Unit = {
    context.parent ! CliqueManager.Connected(cliqueInfo.id, remoteBroker)
    startPingPong()
  }

  def awaitHello(payload: Payload): Unit = payload match {
    case hello: Hello =>
      connection ! BrokerHandler.envelope(HelloAck(selfCliqueInfo, config.brokerInfo.id))
      handle(hello.cliqueInfo, hello.brokerId)
      afterHandShake()
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect Hello")
      stop()
  }

  def handle(cliqueInfo: CliqueInfo, brokerIndex: Int): Unit

  def awaitHelloAck(payload: Payload): Unit = payload match {
    case helloAck: HelloAck =>
      handle(helloAck.cliqueInfo, helloAck.brokerId)
      afterHandShake()
    case err =>
      log.info(s"Got ${err.getClass.getSimpleName}, expect HelloAck")
      stop()
  }

  def handleWith(unaligned: ByteString,
                 current: Payload => Unit,
                 next: Payload    => Unit): Receive = {
    handleEvent(unaligned, current, next) orElse handleOutMessage
  }

  def handleWith(unaligned: ByteString, handle: Payload => Unit): Receive = {
    handleEvent(unaligned, handle, handle) orElse handleOutMessage
  }

  def handleEvent(unaligned: ByteString, handle: Payload => Unit, next: Payload => Unit): Receive = {
    case Tcp.Received(data) =>
      BrokerHandler.deserialize(unaligned ++ data) match {
        case Right((messages, rest)) =>
          messages.foreach { message =>
            val cmdName = message.payload.getClass.getSimpleName
            log.debug(s"Received message of cmd@$cmdName from $remote")
            handle(message.payload)
          }
          context.become(handleWith(rest, next))
        case Left(e) =>
          log.info(
            s"Received corrupted data from $remote; error: ${e.toString}; Closing connection")
          stop()
      }
    case BrokerHandler.SendPing => sendPing()
    case event: Tcp.ConnectionClosed =>
      if (event.isErrorClosed) {
        log.debug(s"Connection closed with error: ${event.getErrorCause}")
      } else {
        log.debug(s"Connection closed normally: $event")
      }
      context stop self
  }

  // TODO: make this safe by using types
  def handleOutMessage: Receive = {
    case message: Message =>
      connection ! BrokerHandler.envelope(message)
    case write: Tcp.Write =>
      connection ! write
  }

  def handlePayload(payload: Payload): Unit = payload match {
    case Ping(nonce, timestamp) =>
      val delay = System.currentTimeMillis() - timestamp
      handlePing(nonce, delay)
    case Pong(nonce) =>
      if (nonce == pingNonce) {
        log.debug("Pong received")
        pingNonce = 0
      } else {
        log.debug(s"Pong received with wrong nonce: expect $pingNonce, got $nonce")
        stop()
      }
    case SendBlocks(blocks) =>
      log.debug(s"Received #${blocks.length} blocks")
      // TODO: support many blocks
      val block      = blocks.head
      val chainIndex = block.chainIndex
      if (chainIndex.relateTo(config.brokerInfo)) {
        val handler = allHandlers.getBlockHandler(chainIndex)
        handler ! BlockChainHandler.AddBlocks(blocks, Remote(cliqueInfo.id))
      } else {
        log.warning(s"Received blocks for wrong chain $chainIndex from $remote")
      }
    case GetBlocks(locators) =>
      log.debug(s"GetBlocks received: #${locators.length}")
      allHandlers.flowHandler ! FlowHandler.GetBlocks(locators)
    case SendHeaders(headers) =>
      log.debug(s"Received #${headers.length} block headers")
      // TODO: support many headers
      val header     = headers.head
      val chainIndex = header.chainIndex
      if (!chainIndex.relateTo(config.brokerInfo)) {
        val handler = allHandlers.getHeaderHandler(chainIndex)
        handler ! HeaderChainHandler.AddHeaders(headers)
      } else {
        log.warning(s"Received headers for wrong chain from $remote")
      }
    case GetHeaders(locators) =>
      log.debug(s"GetHeaders received: ${locators.length}")
      allHandlers.flowHandler ! FlowHandler.GetHeaders(locators)
    case _ =>
      log.warning(s"Got unexpected payload type")
  }

  private var pingNonce: Int = 0

  def handlePing(nonce: Int, delay: Long): Unit = {
    // TODO: refuse ping if it's too frequent
    log.info(s"Ping received with ${delay}ms delay; Replying with Pong")
    connection ! BrokerHandler.envelope(Pong(nonce))
  }

  def sendPing(): Unit = {
    if (pingNonce != 0) {
      log.debug("No Pong message received in time")
      stop()
    } else {
      pingNonce = Random.nextInt()
      val timestamp = System.currentTimeMillis()
      connection ! BrokerHandler.envelope(Ping(pingNonce, timestamp))
    }
  }

  def startPingPong(): Unit = {
    timers.startPeriodicTimer(BrokerHandler.Timer, BrokerHandler.SendPing, config.pingFrequency)
  }

  def stop(): Unit = {
    if (connection != null) {
      connection ! Tcp.Close
    }
    context stop self
  }
}
