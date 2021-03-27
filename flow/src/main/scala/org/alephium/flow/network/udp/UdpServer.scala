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

package org.alephium.flow.network.udp

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{DatagramChannel, SelectionKey, Selector}

import scala.annotation.tailrec
import scala.util.control.NonFatal

import akka.actor.Props
import akka.util.ByteString

import org.alephium.util.{ActorRefT, BaseActor}

object UdpServer {
  var sharedSelectionHandler: SelectionHandler = _

  def props(): Props = Props(new UdpServer())

  sealed trait Command
  final case class Bind(address: InetSocketAddress)                     extends Command
  final case class Send(message: ByteString, remote: InetSocketAddress) extends Command
  private[udp] case object Read                                         extends Command
  private[udp] case object Write                                        extends Command

  sealed trait Event
  final case class Bound(address: InetSocketAddress)                     extends Event
  final case class Received(data: ByteString, sender: InetSocketAddress) extends Event
  case object BindFailed                                                 extends Event
  final case class SendFailed(send: Send, reason: Throwable)             extends Event
}

class UdpServer() extends BaseActor {
  import UdpServer._

  var discoveryServer: ActorRefT[UdpServer.Event] = _
  var channel: DatagramChannel                    = _
  var selectionKey: SelectionKey                  = _

  def receive: Receive = { case Bind(bindAddress) =>
    discoveryServer = ActorRefT[UdpServer.Event](sender())
    try {
      channel = DatagramChannel.open()
      channel.configureBlocking(false)
      channel.socket().setReuseAddress(true)
      channel.socket().bind(bindAddress)

      UdpServer.synchronized {
        if (sharedSelectionHandler == null) {
          val selector   = Selector.open()
          val dispatcher = context.system.dispatchers.lookup(s"akka.io.pinned-dispatcher")

          selectionKey = channel.register(selector, SelectionKey.OP_READ, self)
          sharedSelectionHandler = SelectionHandler(selector, dispatcher)
        } else {
          selectionKey =
            channel.register(sharedSelectionHandler.selector, SelectionKey.OP_READ, self)
          sharedSelectionHandler.selector.wakeup()
        }
      }

      discoveryServer ! Bound(bindAddress)
      context.become(listening)
    } catch {
      case e: Throwable =>
        log.warning(s"Failed in binding udp to [$bindAddress]: $e")
        discoveryServer ! UdpServer.BindFailed
    }
  }

  val buffer: ByteBuffer = ByteBuffer.allocateDirect(128 * 1024) // 128KB
  def listening: Receive = {
    case send @ Send(message, remote) =>
      try {
        buffer.clear()
        message.copyToBuffer(buffer)
        buffer.flip()
        channel.send(buffer, remote)
        ()
      } catch {
        case NonFatal(e) =>
          sender() ! SendFailed(send, e)
        case e: Throwable =>
          log.warning(s"Fatal error: $e, closing UDP server")
          context.stop(self)
      }
    case Read => read(3)
  }

  @tailrec
  private def read(readsLeft: Int): Unit = {
    buffer.clear()
    channel.receive(buffer) match {
      case sender: InetSocketAddress =>
        buffer.flip()
        val data = ByteString(buffer)
        discoveryServer ! UdpServer.Received(data, sender)
      case _ => // null means no data received
    }
    if (readsLeft > 0) read(readsLeft - 1)
  }

  override def postStop(): Unit = {
    if (selectionKey != null) {
      selectionKey.cancel()
    }
    if (channel != null) {
      channel.close()
    }
  }
}
