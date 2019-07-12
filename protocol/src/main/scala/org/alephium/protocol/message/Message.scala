package org.alephium.protocol.message

import akka.util.ByteString
import org.alephium.protocol.Protocol
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde._

case class Message(header: Header, payload: Payload)

object Message {
  def apply[T <: Payload](payload: T): Message = {
    val header = Header(Protocol.version)
    Message(header, payload)
  }

  def serialize(message: Message): ByteString = {
    Serde[Header].serialize(message.header) ++ Payload.serialize(message.payload)
  }

  def serialize[T <: Payload](payload: T): ByteString = {
    serialize(apply(payload))
  }

  def _deserialize(input: ByteString)(
      implicit config: GroupConfig): SerdeResult[(Message, ByteString)] = {
    for {
      headerPair <- Serde[Header]._deserialize(input)
      header = headerPair._1
      rest0  = headerPair._2
      payloadPair <- Payload._deserialize(rest0)
      payload = payloadPair._1
      rest1   = payloadPair._2
    } yield (Message(header, payload), rest1)
  }

  def deserialize(input: ByteString)(implicit config: GroupConfig): SerdeResult[Message] = {
    _deserialize(input).flatMap {
      case (message, rest) =>
        if (rest.isEmpty) Right(message)
        else Left(SerdeError.wrongFormat(s"Too many bytes: #${rest.length} left"))
    }
  }
}
