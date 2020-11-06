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

package org.alephium.api

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import io.circe._
import io.circe.generic.semiauto._

import org.alephium.api.CirceUtils._
import org.alephium.crypto.Sha256
import org.alephium.protocol.{Hash, PublicKey, Signature}
import org.alephium.protocol.config.{ChainsConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.LockupScript
import org.alephium.serde.{serialize, RandomBytes}
import org.alephium.util._

// scalastyle:off number.of.methods
// scalastyle:off number.of.types
object ApiModel {

  final case class Error(code: Int, message: String, data: Option[String])
  object Error {
    def apply(code: Int, message: String): Error = {
      Error(code, message, None)
    }

    implicit val codec: Codec[Error] = deriveCodec[Error]

    // scalastyle:off magic.number
    val ParseError: Error        = Error(-32700, "Parse error")
    val InvalidRequest: Error    = Error(-32600, "Invalid Request")
    val MethodNotFound: Error    = Error(-32601, "Method not found")
    val InvalidParams: Error     = Error(-32602, "Invalid params")
    val InternalError: Error     = Error(-32603, "Internal error")
    val UnauthorizedError: Error = Error(-32604, "Unauthorized")

    def server(error: String): Error = Error(-32000, "Server error", Some(error))
    // scalastyle:on
  }
  trait PerChain {
    val fromGroup: Int
    val toGroup: Int
  }

  final case class TimeInterval(from: TimeStamp, to: TimeStamp)

  final case class FetchRequest(fromTs: TimeStamp, toTs: TimeStamp)

  final case class FetchResponse(blocks: Seq[BlockEntry])

  final case class OutputRef(scriptHint: Int, key: String)
  object OutputRef {
    def from(outputRef: TxOutputRef): OutputRef =
      OutputRef(outputRef.hint.value, outputRef.key.toHexString)
  }

  final case class Input(outputRef: OutputRef, unlockScript: ByteString)
  object Input {
    def from(input: TxInput): Input =
      Input(OutputRef.from(input.outputRef), serialize(input.unlockScript))
  }

  final case class Output(amount: U256, createdHeight: Int, address: Address)
  object Output {
    def from(output: TxOutput, networkType: NetworkType): Output =
      Output(output.amount, output.createdHeight, Address(networkType, output.lockupScript))
  }

  final case class Tx(
      hash: String,
      inputs: AVector[Input],
      outputs: AVector[Output]
  )
  object Tx {
    def from(tx: Transaction, networkType: NetworkType): Tx = Tx(
      tx.hash.toHexString,
      tx.unsigned.inputs.map(Input.from),
      tx.unsigned.fixedOutputs.map(Output.from(_, networkType)) ++
        tx.generatedOutputs.map(Output.from(_, networkType))
    )
  }

  final case class BlockEntry(
      hash: String,
      timestamp: TimeStamp,
      chainFrom: Int,
      chainTo: Int,
      height: Int,
      deps: AVector[String],
      transactions: Option[AVector[Tx]]
  )
  object BlockEntry {

    def from(header: BlockHeader, height: Int)(implicit config: GroupConfig): BlockEntry = {
      BlockEntry(
        hash         = header.hash.toHexString,
        timestamp    = header.timestamp,
        chainFrom    = header.chainIndex.from.value,
        chainTo      = header.chainIndex.to.value,
        height       = height,
        deps         = header.blockDeps.map(_.toHexString),
        transactions = None
      )
    }

    def from(block: Block, height: Int)(implicit config: GroupConfig,
                                        chainsConfig: ChainsConfig): BlockEntry =
      from(block.header, height)
        .copy(transactions = Some(block.transactions.map(Tx.from(_, chainsConfig.networkType))))

  }

  final case class PeerAddress(address: InetAddress, rpcPort: Int, restPort: Int, wsPort: Int)

  final case class SelfClique(cliqueId: CliqueId,
                              peers: AVector[PeerAddress],
                              groupNumPerBroker: Int)

  final case class NeighborCliques(cliques: AVector[InterCliqueInfo])

  final case class GetBalance(address: Address)

  final case class GetGroup(address: Address)

  final case class Balance(balance: U256, utxoNum: Int)
  object Balance {
    def apply(balance_utxoNum: (U256, Int)): Balance = {
      Balance(balance_utxoNum._1, balance_utxoNum._2)
    }
  }

  final case class Group(group: Int)

  final case class CreateTransaction(
      fromKey: PublicKey,
      toAddress: Address,
      value: U256
  )  {
    def fromAddress(networkType: NetworkType): Address =
      Address(networkType, LockupScript.p2pkh(fromKey))
  }

  final case class CreateTransactionResult(unsignedTx: String,
                                           hash: String,
                                           fromGroup: Int,
                                           toGroup: Int)

  object CreateTransactionResult {

    def from(unsignedTx: UnsignedTransaction)(
        implicit groupConfig: GroupConfig): CreateTransactionResult =
      CreateTransactionResult(Hex.toHexString(serialize(unsignedTx)),
                              Hex.toHexString(unsignedTx.hash.bytes),
                              unsignedTx.fromGroup.value,
                              unsignedTx.toGroup.value)
  }

  final case class SendTransaction(tx: String, signature: Signature)

  final case class CreateContract(fromKey: PublicKey, code: String)

  final case class CreateContractResult(unsignedTx: String,
                                        hash: String,
                                        fromGroup: Int,
                                        toGroup: Int)

  object CreateContractResult {
    def from(unsignedTx: UnsignedTransaction)(
        implicit groupConfig: GroupConfig): CreateContractResult =
      CreateContractResult(Hex.toHexString(serialize(unsignedTx)),
                           Hex.toHexString(unsignedTx.hash.bytes),
                           unsignedTx.fromGroup.value,
                           unsignedTx.toGroup.value)
  }

  final case class SendContract(code: String, tx: String, signature: Signature, fromGroup: Int)


  final case class Compile(address: Address, `type`: String, code: String, state: Option[String])

  final case class CompileResult(code: String)

  final case class TxResult(txId: String, fromGroup: Int, toGroup: Int)

  final case class InterCliquePeerInfo(cliqueId: CliqueId,
                                       brokerId: Int,
                                       address: InetSocketAddress,
                                       isSynced: Boolean)


  final case class GetHashesAtHeight(val fromGroup: Int, val toGroup: Int, height: Int)
      extends PerChain

  final case class HashesAtHeight(headers: Seq[String])

  final case class GetChainInfo(val fromGroup: Int, val toGroup: Int)  extends PerChain

  final case class ChainInfo(currentHeight: Int)

  final case class GetBlock(hash: Hash)

  sealed trait MinerAction

  object MinerAction {
    case object StartMining extends MinerAction
    case object StopMining  extends MinerAction
  }

  final case class ApiKey private (val value: String) {
    def hash: Sha256 = Sha256.hash(value)
  }

  object ApiKey {
    def unsafe(raw: String): ApiKey = new ApiKey(raw)

    def createApiKey(raw: String): Either[String, ApiKey] = {
      if (raw.length < 32) {
        Left("Api key must have at least 32 characters")
      } else {
        Right(new ApiKey(raw))
      }
    }
  }
}

trait ApiModelCodec {
  import ApiModel._

  def blockflowFetchMaxAge: Duration
  implicit def networkType: NetworkType

  implicit val u256Encoder: Encoder[U256] = Encoder.encodeJavaBigInteger.contramap[U256](_.toBigInt)
  implicit val u256Decoder: Decoder[U256] = Decoder.decodeJavaBigInteger.emap { u256 =>
    U256.from(u256).toRight(s"Invalid U256: $u256")
  }
  implicit val u256Codec: Codec[U256] = Codec.from(u256Decoder, u256Encoder)

  implicit val publicKeyEncoder: Encoder[PublicKey] = bytesEncoder
  implicit val publicKeyDecoder: Decoder[PublicKey] = bytesDecoder(PublicKey.from)
  implicit val publicKeyCodec: Codec[PublicKey] =
    Codec.from(publicKeyDecoder, publicKeyEncoder)

  implicit val signatureEncoder: Encoder[Signature] = bytesEncoder
  implicit val signatureDecoder: Decoder[Signature] = bytesDecoder(Signature.from)

  implicit val hashEncoder: Encoder[Hash] = hash => Json.fromString(hash.toHexString)
  implicit val hashDecoder: Decoder[Hash] =
    byteStringDecoder.emap(Hash.from(_).toRight("cannot decode hash"))
  implicit val hashCodec: Codec[Hash] = Codec.from(hashDecoder, hashEncoder)

  lazy val addressEncoder: Encoder[Address] =
    Encoder.encodeString.contramap[Address](_.toBase58)
  lazy val addressDecoder: Decoder[Address] =
    Decoder.decodeString.emap { input =>
      Address
        .fromBase58(input, networkType)
        .toRight(s"Unable to decode address from $input")
    }
  implicit lazy val addressCodec: Codec[Address] = Codec.from(addressDecoder, addressEncoder)

  implicit val cliqueIdEncoder: Encoder[CliqueId] = Encoder.encodeString.contramap(_.toHexString)
  implicit val cliqueIdDecoder: Decoder[CliqueId] = Decoder.decodeString.emap(createCliqueId)
  implicit val cliqueIdCodec: Codec[CliqueId]     = Codec.from(cliqueIdDecoder, cliqueIdEncoder)

  implicit val fetchResponseCodec: Codec[FetchResponse] = deriveCodec[FetchResponse]

  implicit val outputRefCodec: Codec[OutputRef] = deriveCodec[OutputRef]

  implicit val inputCodec: Codec[Input] = deriveCodec[Input]

  implicit val outputCodec: Codec[Output] = deriveCodec[Output]

  implicit val txCodec: Codec[Tx] = deriveCodec[Tx]

  implicit val blockEntryCodec: Codec[BlockEntry] = deriveCodec[BlockEntry]

  implicit val peerAddressCodec: Codec[PeerAddress] = deriveCodec[PeerAddress]

  implicit val selfCliqueCodec: Codec[SelfClique] = deriveCodec[SelfClique]

  implicit val neighborCliquesCodec: Codec[NeighborCliques] = deriveCodec[NeighborCliques]

  implicit val getBalanceCodec: Codec[GetBalance] = deriveCodec[GetBalance]

  implicit val getGroupCodec: Codec[GetGroup] = deriveCodec[GetGroup]

  implicit val balanceCodec: Codec[Balance] = deriveCodec[Balance]

  implicit val createTransactionCodec: Codec[CreateTransaction] = deriveCodec[CreateTransaction]

  implicit val groupCodec: Codec[Group] = deriveCodec[Group]

  implicit val createTransactionResultCodec: Codec[CreateTransactionResult] =
    deriveCodec[CreateTransactionResult]

  implicit val sendTransactionCodec: Codec[SendTransaction] = deriveCodec[SendTransaction]

  implicit val createContractCodec: Codec[CreateContract] = deriveCodec[CreateContract]

  implicit val createContractResultCodec: Codec[CreateContractResult] =
    deriveCodec[CreateContractResult]

  implicit val sendContractCodec: Codec[SendContract] = deriveCodec[SendContract]

  implicit val compileResult: Codec[Compile] = deriveCodec[Compile]

  implicit val compileResultCodec: Codec[CompileResult] = deriveCodec[CompileResult]

  implicit val txResultCodec: Codec[TxResult] = deriveCodec[TxResult]

  implicit val getHashesAtHeightCodec: Codec[GetHashesAtHeight] = deriveCodec[GetHashesAtHeight]

  implicit val hashesAtHeightCodec: Codec[HashesAtHeight] = deriveCodec[HashesAtHeight]

  implicit val getChainInfoCodec: Codec[GetChainInfo] = deriveCodec[GetChainInfo]

  implicit val chainInfoCodec: Codec[ChainInfo] = deriveCodec[ChainInfo]

  implicit val getBlockCodec: Codec[GetBlock] = deriveCodec[GetBlock]

  implicit val minerActionDecoder: Decoder[MinerAction] = Decoder[String].emap {
    case "start-mining" => Right(MinerAction.StartMining)
    case "stop-mining"  => Right(MinerAction.StopMining)
    case other          => Left(s"Invalid miner action: $other")
  }
  implicit val minerActionEncoder: Encoder[MinerAction] = Encoder[String].contramap {
    case MinerAction.StartMining => "start-mining"
    case MinerAction.StopMining  => "stop-mining"
  }
  implicit val minerActionCodec: Codec[MinerAction] =
    Codec.from(minerActionDecoder, minerActionEncoder)

  implicit val apiKeyEncoder: Encoder[ApiKey] = Encoder.encodeString.contramap(_.value)
  implicit val apiKeyDecoder: Decoder[ApiKey] = Decoder.decodeString.emap(ApiKey.createApiKey)
  implicit val apiKeyCodec: Codec[ApiKey]     = Codec.from(apiKeyDecoder, apiKeyEncoder)

  implicit val cliqueEncoder: Encoder[InterCliqueInfo] =
    Encoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(info =>
      (info.id, info.externalAddresses, info.groupNumPerBroker))
  implicit val cliqueDecoder: Decoder[InterCliqueInfo] =
    Decoder.forProduct3("id", "externalAddresses", "groupNumPerBroker")(InterCliqueInfo.unsafe)

  implicit val interCliqueSyncedStatusCodec: Codec[InterCliquePeerInfo] =
    deriveCodec[InterCliquePeerInfo]

  lazy val fetchRequestDecoder: Decoder[FetchRequest] =
    deriveDecoder[FetchRequest]
      .ensure(
        fetchRequest => fetchRequest.fromTs <= fetchRequest.toTs,
        "`toTs` cannot be before `fromTs`"
      )
      .ensure(
        fetchRequest =>
          (fetchRequest.toTs -- fetchRequest.fromTs)
            .exists(_ <= blockflowFetchMaxAge),
        s"interval cannot be greater than ${blockflowFetchMaxAge}"
      )
  val fetchRequestEncoder: Encoder[FetchRequest] = deriveEncoder[FetchRequest]
  implicit lazy val fetchRequestCodec: Codec[FetchRequest] =
    Codec.from(fetchRequestDecoder, fetchRequestEncoder)

  private def bytesEncoder[T <: RandomBytes]: Encoder[T] =
    Encoder.encodeString.contramap[T](_.toHexString)
  private def bytesDecoder[T](from: ByteString => Option[T]): Decoder[T] =
    Decoder.decodeString.emap { input =>
      val keyOpt = for {
        bs  <- Hex.from(input)
        key <- from(bs)
      } yield key
      keyOpt.toRight(s"Unable to decode key from $input")
    }

  private def createCliqueId(s: String): Either[String, CliqueId] = {
    Hex.from(s).flatMap(CliqueId.from) match {
      case Some(id) => Right(id)
      case None     => Left("invalid clique id")
    }
  }
}
