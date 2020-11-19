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

import io.circe.{Codec, Decoder, Encoder}
import io.circe.parser._
import io.circe.syntax._
import org.scalacheck.Gen
import org.scalatest.{Assertion, EitherValues}

import org.alephium.api.CirceUtils
import org.alephium.api.CirceUtils._
import org.alephium.api.model._
import org.alephium.protocol.{PublicKey, Signature}
import org.alephium.protocol.model.{Address, CliqueId, CliqueInfo, NetworkType}
import org.alephium.util._

class ApiModelSpec extends AlephiumSpec with ApiModelCodec with EitherValues with NumericHelpers {
  def show[T](t: T)(implicit encoder: Encoder[T]): String = {
    CirceUtils.print(t.asJson)
  }

  def entryDummy(i: Int): BlockEntry =
    BlockEntry(i.toString, TimeStamp.unsafe(i.toLong), i, i, i, AVector(i.toString), None)
  val dummyAddress = new InetSocketAddress("127.0.0.1", 9000)
  val dummyCliqueInfo =
    CliqueInfo.unsafe(CliqueId.generate, AVector(Option(dummyAddress)), AVector(dummyAddress), 1)

  val blockflowFetchMaxAge = Duration.unsafe(1000)

  val networkType = NetworkType.Mainnet

  def generateKeyHash(): String = {
    val address = PublicKey.generate
    Hex.toHexString(address.bytes)
  }

  def generateAddress(): Address = Address.p2pkh(networkType, PublicKey.generate)

  def parseAs[A](jsonRaw: String)(implicit A: Decoder[A]): A = {
    val json = parse(jsonRaw).toOption.get
    json.as[A].toOption.get
  }

  def checkData[T](data: T, jsonRaw: String)(implicit codec: Codec[T]): Assertion = {
    show(data) is jsonRaw
    parseAs[T](jsonRaw) is data
  }

  def parseFail[A](jsonRaw: String)(implicit A: Decoder[A]): String = {
    parse(jsonRaw).toOption.get.as[A].left.value.message
  }

  it should "encode/decode TimeStamp" in {
    checkData(TimeStamp.unsafe(0), "0")

    forAll(Gen.posNum[Long]) { long =>
      val timestamp = TimeStamp.unsafe(long)
      checkData(timestamp, s"$long")
    }

    forAll(Gen.negNum[Long]) { long =>
      parseFail[TimeStamp](s"$long") is "expect positive timestamp"
    }
  }

  it should "encode/decode FetchRequest" in {
    val request =
      FetchRequest(TimeStamp.unsafe(1L), TimeStamp.unsafe(42L))
    val jsonRaw = """{"fromTs":1,"toTs":42}"""
    checkData(request, jsonRaw)
  }

  it should "validate FetchRequest" in {
    parseFail[FetchRequest]("""{"fromTs":42,"toTs":1}""") is "`toTs` cannot be before `fromTs`"
    parseFail[FetchRequest]("""{"fromTs":1,"toTs":100000}""") is s"interval cannot be greater than $blockflowFetchMaxAge"
    parseFail[FetchRequest]("""{}""") is s"Attempt to decode value on failed cursor"
  }

  it should "encode/decode empty FetchResponse" in {
    val response = FetchResponse(Seq.empty)
    val jsonRaw =
      """{"blocks":[]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode FetchResponse" in {
    val response = FetchResponse((0 to 1).map(entryDummy))
    val jsonRaw =
      """{"blocks":[{"hash":"0","timestamp":0,"chainFrom":0,"chainTo":0,"height":0,"deps":["0"]},{"hash":"1","timestamp":1,"chainFrom":1,"chainTo":1,"height":1,"deps":["1"]}]}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode SelfClique" in {
    val cliqueId    = CliqueId.generate
    val peerAddress = PeerAddress(InetAddress.getByName("127.0.0.1"), 9000, 9001, 9002)
    val selfClique  = SelfClique(cliqueId, AVector(peerAddress), 1)
    val jsonRaw =
      s"""{"cliqueId":"${cliqueId.toHexString}","peers":[{"address":"127.0.0.1","rpcPort":9000,"restPort":9001,"wsPort":9002}],"groupNumPerBroker":1}"""
    checkData(selfClique, jsonRaw)
  }

  it should "encode/decode NeighborCliques" in {
    val neighborCliques = NeighborCliques(AVector(dummyCliqueInfo.interCliqueInfo.get))
    val cliqueIdString  = dummyCliqueInfo.id.toHexString
    def jsonRaw(cliqueId: String) =
      s"""{"cliques":[{"id":"$cliqueId","externalAddresses":[{"addr":"127.0.0.1","port":9000}],"groupNumPerBroker":1}]}"""
    checkData(neighborCliques, jsonRaw(cliqueIdString))

    parseFail[NeighborCliques](jsonRaw("OOPS")) is "invalid clique id"
  }

  it should "encode/decode GetBalance" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val request    = GetBalance(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode Output with big amount" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val amount     = U256.unsafe(15).mulUnsafe(U256.unsafe(Number.quintillion))
    val amountStr  = "15000000000000000000"
    val request    = Output(amount, address)
    val jsonRaw    = s"""{"amount":$amountStr,"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode GetGroup" in {
    val address    = generateAddress()
    val addressStr = address.toBase58
    val request    = GetGroup(address)
    val jsonRaw    = s"""{"address":"$addressStr"}"""
    checkData(request, jsonRaw)
  }

  it should "encode/decode Balance" in {
    val response = Balance(100, 1)
    val jsonRaw  = """{"balance":100,"utxoNum":1}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode Group" in {
    val response = Group(42)
    val jsonRaw  = """{"group":42}"""
    checkData(response, jsonRaw)
  }

  it should "encode/decode TxResult" in {
    val result  = TxResult("txId", 0, 1)
    val jsonRaw = """{"txId":"txId","fromGroup":0,"toGroup":1}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode BuildTransaction" in {
    val fromKey   = PublicKey.generate
    val toKey     = PublicKey.generate
    val toAddress = Address.p2pkh(networkType, toKey)
    val transfer  = BuildTransaction(fromKey, toAddress, 1)
    val jsonRaw =
      s"""{"fromKey":"${fromKey.toHexString}","toAddress":"${toAddress.toBase58}","value":1}"""
    checkData(transfer, jsonRaw)
  }

  it should "encode/decode BuildTransactionResult" in {
    val result  = BuildTransactionResult("tx", "txHash", 1, 2)
    val jsonRaw = """{"unsignedTx":"tx","hash":"txHash","fromGroup":1,"toGroup":2}"""
    checkData(result, jsonRaw)
  }

  it should "encode/decode SendTransaction" in {
    val signature = Signature.generate
    val transfer  = SendTransaction("tx", signature)
    val jsonRaw =
      s"""{"tx":"tx","signature":"${signature.toHexString}"}"""
    checkData(transfer, jsonRaw)
  }
}
