package org.alephium.protocol.script

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.model.RawTransaction
import org.alephium.serde.Serde
import org.alephium.util.AVector

final case class Witness(privateScript: AVector[Instruction], signatures: AVector[ED25519Signature])

object Witness {
  implicit val serde: Serde[Witness] =
    Serde.forProduct2(Witness(_, _), t => (t.privateScript, t.signatures))

  // TODO: optimize this using cache
  def p2pkh(rawTransaction: RawTransaction,
            publicKey: ED25519PublicKey,
            privateKey: ED25519PrivateKey): Witness = {
    val signature = ED25519.sign(rawTransaction.hash.bytes, privateKey)
    Witness(AVector[Instruction](OP_PUSH.unsafe(publicKey.bytes)), AVector(signature))
  }
}
