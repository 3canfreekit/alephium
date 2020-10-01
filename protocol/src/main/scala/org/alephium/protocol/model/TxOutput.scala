package org.alephium.protocol.model

import akka.util.ByteString

import org.alephium.protocol.{ALF, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde._
import org.alephium.util.{AVector, U64}

sealed trait TxOutput {
  def amount: U64
  def createdHeight: Int
  def lockupScript: LockupScript
  def tokens: AVector[(TokenId, U64)]

  def hint: Hint

  def isAsset: Boolean
}

object TxOutput {
  implicit val serde: Serde[TxOutput] = eitherSerde[AssetOutput, ContractOutput].xmap(
    {
      case Left(assetOutput)     => assetOutput
      case Right(contractOutput) => contractOutput
    }, {
      case output: AssetOutput    => Left(output)
      case output: ContractOutput => Right(output)
    }
  )

  def asset(amount: U64, createdHeight: Int, lockupScript: LockupScript): AssetOutput = {
    AssetOutput(amount, createdHeight, lockupScript, AVector.empty, ByteString.empty)
  }

  def contract(amount: U64, createdHeight: Int, lockupScript: LockupScript): ContractOutput = {
    ContractOutput(amount, createdHeight, lockupScript, AVector.empty)
  }

  def genesis(amount: U64, lockupScript: LockupScript): AssetOutput = {
    asset(amount, ALF.GenesisHeight, lockupScript)
  }

  // TODO: improve this when vm is mature
  def forMPT: TxOutput =
    ContractOutput(U64.One, ALF.GenesisHeight, LockupScript.p2pkh(Hash.zero), AVector.empty)
}

/**
  *
  * @param amount the number of ALF in the output
  * @param createdHeight height when the output was created, might be smaller than the block height
  * @param lockupScript guarding script for unspent output
  * @param tokens secondary tokens in the output
  * @param additionalData data payload for additional information
  */
final case class AssetOutput(amount: U64,
                             createdHeight: Int,
                             lockupScript: LockupScript,
                             tokens: AVector[(TokenId, U64)],
                             additionalData: ByteString)
    extends TxOutput {
  def isAsset: Boolean = true

  def hint: Hint = Hint.from(this)

  def toGroup(implicit config: GroupConfig): GroupIndex = lockupScript.groupIndex
}

object AssetOutput {
  private[model] implicit val tokenSerde: Serde[(TokenId, U64)] = Serde.tuple2[TokenId, U64]
  implicit val serde: Serde[AssetOutput] =
    Serde.forProduct5(AssetOutput.apply,
                      t => (t.amount, t.createdHeight, t.lockupScript, t.tokens, t.additionalData))
}

final case class ContractOutput(amount: U64,
                                createdHeight: Int,
                                lockupScript: LockupScript,
                                tokens: AVector[(TokenId, U64)])
    extends TxOutput {
  def isAsset: Boolean = false

  def hint: Hint = Hint.from(this)
}

object ContractOutput {
  import AssetOutput.tokenSerde
  implicit val serde: Serde[ContractOutput] =
    Serde.forProduct4(ContractOutput.apply,
                      t => (t.amount, t.createdHeight, t.lockupScript, t.tokens))
}
