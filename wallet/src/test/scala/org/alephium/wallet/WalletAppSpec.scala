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

package org.alephium.wallet

import java.net.InetAddress
import java.nio.file.Files

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.SocketUtil
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import io.circe.syntax._
import org.scalatest.concurrent.ScalaFutures

import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.{Hash, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, NetworkType, TxGenerators}
import org.alephium.serde.serialize
import org.alephium.util.{AlephiumSpec, AVector, Hex, U256}
import org.alephium.wallet.api.WalletApiError
import org.alephium.wallet.api.model
import org.alephium.wallet.circe.ModelCodecs
import org.alephium.wallet.config.WalletConfig

class WalletAppSpec
    extends AlephiumSpec
    with ModelCodecs
    with ScalatestRouteTest
    with FailFastCirceSupport
    with ScalaFutures {
  implicit val defaultTimeout = RouteTestTimeout(5.seconds)

  val localhost: InetAddress = InetAddress.getLocalHost
  val blockFlowPort          = SocketUtil.temporaryLocalPort(SocketUtil.Both)
  val walletPort             = SocketUtil.temporaryLocalPort(SocketUtil.Both)

  val groupNum = 4

  implicit val groupConfig: GroupConfig = new GroupConfig {
    override def groups: Int = groupNum
  }

  val networkType = NetworkType.Mainnet

  val blockFlowMock =
    new WalletAppSpec.BlockFlowServerMock(localhost, blockFlowPort, networkType)
  val blockflowBinding = blockFlowMock.server.futureValue

  val tempSecretDir = Files.createTempDirectory("blockflow-wallet-spec")
  tempSecretDir.toFile.deleteOnExit

  val config = WalletConfig(
    walletPort,
    tempSecretDir,
    networkType,
    WalletConfig.BlockFlow(localhost.getHostAddress, blockFlowPort, groupNum))

  val walletApp: WalletApp =
    new WalletApp(config)

  val routes: Route = walletApp.routes

  walletApp.start()

  val password                   = Hash.generate.toHexString
  var mnemonic: Mnemonic         = _
  var addresses: model.Addresses = _
  var address: Address           = _
  var wallet: String             = _
  val (_, transferPublicKey)     = SignatureSchema.generatePriPub()
  val transferAddress            = Address.p2pkh(networkType, transferPublicKey).toBase58
  val transferAmount             = 10
  val balanceAmount              = U256.unsafe(42)

  def creationJson(size: Int)                   = s"""{"password":"$password","mnemonicSize":$size}"""
  val unlockJson                                = s"""{"password":"$password"}"""
  def transferJson(amount: Int)                 = s"""{"address":"$transferAddress","amount":$amount}"""
  def changeActiveAddressJson(address: Address) = s"""{"address":"${address.toBase58}"}"""
  def restoreJson(mnemonic: Mnemonic) =
    s"""{"password":"$password","mnemonic":${mnemonic.asJson}}"""

  def create(size: Int)           = Post(s"/wallets", entity(creationJson(size))) ~> routes
  def restore(mnemonic: Mnemonic) = Put(s"/wallets", entity(restoreJson(mnemonic))) ~> routes
  def unlock()                    = Post(s"/wallets/${wallet}/unlock", entity(unlockJson)) ~> routes
  def lock()                      = Post(s"/wallets/${wallet}/lock") ~> routes
  def getBalance()                = Get(s"/wallets/${wallet}/balances") ~> routes
  def getAddresses()              = Get(s"/wallets/${wallet}/addresses") ~> routes
  def transfer(amount: Int) =
    Post(s"/wallets/${wallet}/transfer", entity(transferJson(amount))) ~> routes
  def deriveNextAddress() = Post(s"/wallets/${wallet}/deriveNextAddress") ~> routes
  def changeActiveAddress(address: Address) =
    Post(s"/wallets/${wallet}/changeActiveAddress", entity(changeActiveAddressJson(address))) ~> routes
  def listWallets() = Get(s"/wallets") ~> routes

  def entity(json: String) = HttpEntity(ContentTypes.`application/json`, json)

  it should "work" in {

    unlock() ~> check {
      status is StatusCodes.BadRequest
    }

    create(2) ~> check {
      val error = responseAs[WalletApiError]
      error.detail is s"""Invalid value for: body (Invalid mnemonic size: 2, expected: 12, 15, 18, 21, 24: DownField(mnemonicSize): {"password":"$password","mnemonicSize":2})"""
      status is StatusCodes.BadRequest
    }

    create(24) ~> check {
      val result = responseAs[model.WalletCreation.Result]
      mnemonic = result.mnemonic
      wallet   = result.walletName
      status is StatusCodes.OK
    }

    listWallets() ~> check {
      val walletStatus = responseAs[AVector[model.WalletStatus]].head
      walletStatus.walletName is wallet
      walletStatus.locked is false
      wallet is walletStatus.walletName
      status is StatusCodes.OK
    }

    //Lock is idempotent
    (0 to 10).foreach { _ =>
      lock() ~> check {
        status is StatusCodes.OK
      }
    }

    getBalance() ~> check {
      status is StatusCodes.Unauthorized
    }

    getAddresses() ~> check {
      status is StatusCodes.Unauthorized
    }

    transfer(transferAmount) ~> check {
      status is StatusCodes.Unauthorized
    }

    unlock()

    getAddresses() ~> check {
      addresses = responseAs[model.Addresses]
      address   = addresses.activeAddress
      status is StatusCodes.OK
    }

    getBalance() ~> check {
      responseAs[model.Balances] is model.Balances(
        balanceAmount,
        AVector(model.Balances.AddressBalance(address, balanceAmount)))
      status is StatusCodes.OK
    }

    transfer(transferAmount) ~> check {
      status is StatusCodes.OK
      responseAs[model.Transfer.Result]
    }

    val negAmount = -10
    transfer(negAmount) ~> check {
      val error = responseAs[WalletApiError]
      error.detail is s"""Invalid value for: body (Invalid U256: $negAmount: DownField(amount): {"address":"$transferAddress","amount":$negAmount})"""
      status is StatusCodes.BadRequest
    }

    deriveNextAddress() ~> check {
      address   = responseAs[Address]
      addresses = model.Addresses(address, addresses.addresses :+ address)
      status is StatusCodes.OK
    }

    getAddresses() ~> check {
      responseAs[model.Addresses] is addresses
      status is StatusCodes.OK
    }

    address   = addresses.addresses.head
    addresses = addresses.copy(activeAddress = address)

    changeActiveAddress(address) ~> check {
      status is StatusCodes.OK
    }

    getAddresses() ~> check {
      responseAs[model.Addresses] is addresses
      status is StatusCodes.OK
    }

    val newMnemonic = Mnemonic.generate(24).get
    restore(newMnemonic) ~> check {
      wallet = responseAs[model.WalletRestore.Result].walletName
      status is StatusCodes.OK
    }

    listWallets() ~> check {
      val walletStatuses = responseAs[AVector[model.WalletStatus]]
      walletStatuses.length is 2
      walletStatuses.map(_.walletName).contains(wallet)
      status is StatusCodes.OK
    }

    Get(s"/docs") ~> routes ~> check {
      status is StatusCodes.PermanentRedirect
    }

    Get(s"/docs/openapi.yaml") ~> routes ~> check {
      status is StatusCodes.OK
    }

    tempSecretDir.toFile.listFiles.foreach(_.deleteOnExit())
  }
}

object WalletAppSpec extends {
  import org.alephium.wallet.web.BlockFlowClient._

  class BlockFlowServerMock(address: InetAddress, port: Int, val networkType: NetworkType)(
      implicit val groupConfig: GroupConfig,
      system: ActorSystem)
      extends FailFastCirceSupport
      with TxGenerators
      with Codecs {

    private val peer = PeerAddress(address, Some(port), None)

    val routes: Route =
      post {
        entity(as[JsonRpc]) {
          case GetSelfClique =>
            complete(Result(SelfClique(ArraySeq(peer, peer), 2)))
          case GetBalance(_) =>
            complete(Result(Balance(42, 1)))
          case CreateTransaction(_, _, _) =>
            val unsignedTx = transactionGen().sample.get.unsigned
            complete(
              Result(
                CreateTransactionResult(Hex.toHexString(serialize(unsignedTx)),
                                        Hex.toHexString(unsignedTx.hash.bytes),
                                        unsignedTx.fromGroup.value,
                                        unsignedTx.toGroup.value)
              ))
          case SendTransaction(_, _) =>
            complete(Result(TxResult(Hash.generate, 0, 0)))
        }
      }

    val server = Http().bindAndHandle(routes, address.getHostAddress, port)

    implicit val jsonRpcDecoder: Decoder[JsonRpc] = new Decoder[JsonRpc] {
      def decode(c: HCursor, method: String, params: Json): Decoder.Result[JsonRpc] = {
        method match {
          case "send_transaction"   => params.as[SendTransaction]
          case "create_transaction" => params.as[CreateTransaction]
          case "get_balance"        => params.as[GetBalance]
          case "self_clique"        => Right(GetSelfClique)
          case _                    => Left(DecodingFailure(s"$method not supported", c.history))
        }
      }
      final def apply(c: HCursor): Decoder.Result[JsonRpc] =
        for {
          method  <- c.downField("method").as[String]
          params  <- c.downField("params").as[Json]
          jsonRpc <- decode(c, method, params)
        } yield jsonRpc
    }
  }
}
