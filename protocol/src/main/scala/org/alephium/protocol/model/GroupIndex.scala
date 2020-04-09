package org.alephium.protocol.model

import scala.annotation.tailrec

import org.alephium.crypto._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.script.{PayTo, PubScript}
import org.alephium.util.Bits

class GroupIndex(val value: Int) extends AnyVal {
  override def toString: String = s"GroupIndex($value)"

  @tailrec
  final def generateKey(payTo: PayTo)(
      implicit config: GroupConfig): (ED25519PrivateKey, ED25519PublicKey) = {
    val (privateKey, publicKey) = ED25519.generatePriPub()
    val pubScript               = PubScript.build(payTo, publicKey)
    if (GroupIndex.from(pubScript) == this) (privateKey, publicKey)
    else generateKey(payTo)
  }
}

object GroupIndex {
  def unsafe(group: Int)(implicit config: GroupConfig): GroupIndex = {
    assume(validate(group))
    new GroupIndex(group)
  }

  def from(group: Int)(implicit config: GroupConfig): Option[GroupIndex] = {
    if (validate(group)) {
      Some(new GroupIndex(group))
    } else {
      None
    }
  }

  @inline
  private def validate(group: Int)(implicit config: GroupConfig): Boolean =
    0 <= group && group < config.groups

  def from(payTo: PayTo, publicKey: ED25519PublicKey)(implicit config: GroupConfig): GroupIndex = {
    val pubScript = PubScript.build(payTo, publicKey)
    from(pubScript)
  }

  def from(pubScript: PubScript)(implicit config: GroupConfig): GroupIndex = {
    fromShortKey(pubScript.shortKey)
  }

  def fromShortKey(shortKey: Int)(implicit config: GroupConfig): GroupIndex = {
    val hash = Bits.toPosInt(Bits.xorByte(shortKey))
    GroupIndex.unsafe(hash % config.groups)
  }
}
