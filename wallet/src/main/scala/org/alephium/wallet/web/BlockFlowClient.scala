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

package org.alephium.wallet.web

import java.net.InetAddress

import scala.collection.immutable.ArraySeq
import scala.concurrent.{ExecutionContext, Future}

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, Uri}
import io.circe.{Codec, Decoder, Encoder, Json, JsonObject}
import io.circe.generic.semiauto.{deriveCodec, deriveDecoder, deriveEncoder}
import io.circe.syntax._

import org.alephium.protocol.{Hash, PublicKey, Signature}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex, NetworkType}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{Hex, U256}
import org.alephium.wallet.circe.ProtocolCodecs

trait BlockFlowClient {
  def getBalance(address: Address): Future[Either[String, U256]]
  def prepareTransaction(
      fromKey: String,
      toAddress: Address,
      value: U256): Future[Either[String, BlockFlowClient.CreateTransactionResult]]
  def sendTransaction(tx: String,
                      signature: Signature,
                      fromGroup: Int): Future[Either[String, BlockFlowClient.TxResult]]
}

object BlockFlowClient {
  def apply(httpClient: HttpClient, address: Uri, groupNum: Int, networkType: NetworkType)(
      implicit executionContext: ExecutionContext): BlockFlowClient =
    new Impl(httpClient, address, groupNum, networkType)

  private class Impl(httpClient: HttpClient,
                     address: Uri,
                     groupNum: Int,
                     val networkType: NetworkType)(implicit executionContext: ExecutionContext)
      extends BlockFlowClient
      with Codecs {

    implicit private val groupConfig: GroupConfig = new GroupConfig { val groups = groupNum }
    private def rpcRequest[P <: JsonRpc: Encoder](uri: Uri, jsonRpc: P): HttpRequest =
      HttpRequest(
        HttpMethods.POST,
        uri = uri,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          s"""{"jsonrpc":"2.0","id": 0,"method":"${jsonRpc.method}","params":${jsonRpc.asJson}}""")
      )

    private def request[P <: JsonRpc: Encoder, R: Codec](params: P,
                                                         uri: Uri): Future[Either[String, R]] = {
      httpClient
        .request[Result[R]](
          rpcRequest(uri, params)
        )
        .map(_.map(_.result))
    }

    private def requestFromGroup[P <: JsonRpc: Encoder, R: Codec](
        fromGroup: GroupIndex,
        params: P
    ): Future[Either[String, R]] =
      uriFromGroup(fromGroup).flatMap {
        _.fold(
          error => Future.successful(Left(error)),
          uri   => request[P, R](params, uri)
        )
      }

    private def uriFromGroup(fromGroup: GroupIndex): Future[Either[String, Uri]] =
      getSelfClique().map { selfCliqueEither =>
        for {
          selfClique <- selfCliqueEither
          peer = selfClique.peer(fromGroup)
          rpcPort <- peer.rpcPort.toRight(
            s"No rpc port for group ${fromGroup.value} (peers: ${selfClique.peers})")
        } yield {
          Uri(s"http://${peer.address.getHostAddress}:$rpcPort")
        }
      }

    def getBalance(address: Address): Future[Either[String, U256]] =
      requestFromGroup[GetBalance, Balance](
        address.groupIndex,
        GetBalance(address)
      ).map(_.map(_.balance))

    def prepareTransaction(fromKey: String,
                           toAddress: Address,
                           value: U256): Future[Either[String, CreateTransactionResult]] = {
      Hex.from(fromKey).flatMap(PublicKey.from).map(LockupScript.p2pkh) match {
        case None => Future.successful(Left(s"Cannot decode key $fromKey"))
        case Some(lockupScript) =>
          requestFromGroup[CreateTransaction, CreateTransactionResult](
            lockupScript.groupIndex,
            CreateTransaction(fromKey, toAddress, value)
          )
      }
    }

    def sendTransaction(tx: String,
                        signature: Signature,
                        fromGroup: Int): Future[Either[String, BlockFlowClient.TxResult]] = {
      requestFromGroup[SendTransaction, TxResult](
        GroupIndex.unsafe(fromGroup),
        SendTransaction(tx, signature.toHexString)
      )
    }

    private def getSelfClique(): Future[Either[String, SelfClique]] =
      request[GetSelfClique.type, SelfClique](
        GetSelfClique,
        address
      )
  }

  final case class Result[A: Codec](result: A)

  sealed trait JsonRpc {
    def method: String
  }

  final case object GetSelfClique extends JsonRpc {
    val method: String = "self_clique"
    implicit val encoder: Encoder[GetSelfClique.type] = new Encoder[GetSelfClique.type] {
      final def apply(selfClique: GetSelfClique.type): Json = JsonObject.empty.asJson
    }
  }

  final case class GetBalance(address: Address) extends JsonRpc {
    val method: String = "get_balance"
  }

  final case class Balance(balance: U256, utxoNum: Int)

  final case class CreateTransaction(
      fromKey: String,
      toAddress: Address,
      value: U256
  ) extends JsonRpc {
    val method: String = "create_transaction"
  }

  final case class CreateTransactionResult(unsignedTx: String,
                                           hash: String,
                                           fromGroup: Int,
                                           toGroup: Int)

  final case class SendTransaction(tx: String, signature: String) extends JsonRpc {
    val method: String = "send_transaction"
  }

  final case class TxResult(txId: Hash, fromGroup: Int, toGroup: Int)

  final case class PeerAddress(address: InetAddress, rpcPort: Option[Int], wsPort: Option[Int])

  final case class SelfClique(peers: ArraySeq[PeerAddress], groupNumPerBroker: Int) {
    def peer(groupIndex: GroupIndex): PeerAddress =
      peers((groupIndex.value / groupNumPerBroker) % peers.length)
  }

  trait Codecs extends ProtocolCodecs {

    def networkType: NetworkType

    implicit def resultCodec[A: Codec]: Codec[Result[A]] = deriveCodec[Result[A]]

    implicit lazy val balanceCodec: Codec[Balance] = deriveCodec[Balance]
    implicit lazy val createTransactionCodec: Codec[CreateTransaction] =
      deriveCodec[CreateTransaction]
    implicit lazy val createTransactionResultCodec: Codec[CreateTransactionResult] =
      deriveCodec[CreateTransactionResult]
    implicit lazy val getBalanceCodec: Codec[GetBalance]     = deriveCodec[GetBalance]
    implicit lazy val peerAddressCodec: Codec[PeerAddress]   = deriveCodec[PeerAddress]
    implicit lazy val selfCliqueEncoder: Encoder[SelfClique] = deriveEncoder[SelfClique]
    implicit lazy val selfCliqueDecoder: Decoder[SelfClique] =
      deriveDecoder[SelfClique]
        .ensure(_.groupNumPerBroker > 0, "Non-positve groupNumPerBroker")
        .ensure(_.peers.nonEmpty, "Zero number of peers")
    implicit lazy val selfCliqueCodec: Codec[SelfClique] =
      Codec.from(selfCliqueDecoder, selfCliqueEncoder)
    implicit lazy val sendTransactionCodec: Codec[SendTransaction] = deriveCodec[SendTransaction]
    implicit lazy val txResultCodec: Codec[TxResult]               = deriveCodec[TxResult]
  }
}
