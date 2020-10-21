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

package org.alephium.appserver

import scala.concurrent.Future

import com.typesafe.scalalogging.StrictLogging
import sttp.tapir._
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.PartialServerEndpoint

import org.alephium.appserver.ApiModel._
import org.alephium.appserver.TapirCodecs
import org.alephium.appserver.TapirSchemas._
import org.alephium.crypto.Sha256
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.rpc.model.JsonRPC._
import org.alephium.util.{TimeStamp, U256}

trait Endpoints extends ApiModelCodec with TapirCodecs with StrictLogging {

  implicit def apiConfig: ApiConfig
  implicit def groupConfig: GroupConfig

  type BaseEndpoint[A, B] = Endpoint[A, Response.Failure, B, Nothing]
  type AuthEndpoint[A, B] = PartialServerEndpoint[ApiKey, A, Response.Failure, B, Nothing, Future]

  private val apiKeyHash = apiConfig.apiKeyHash.getOrElse {
    val apiKey = Hash.generate.toHexString
    logger.info(s"Api Key is '$apiKey'")
    Sha256.hash(apiKey)
  }

  private val timeIntervalQuery: EndpointInput[TimeInterval] =
    query[TimeStamp]("fromTs")
      .and(query[TimeStamp]("toTs"))
      .validate(
        Validator.custom({ case (from, to) => from <= to }, "`fromTs` must be before `toTs`"))
      .map({ case (from, to) => TimeInterval(from, to) })(timeInterval =>
        (timeInterval.from, timeInterval.to))

  private def checkApiKey(apiKey: ApiKey): Either[Response.Failure, ApiKey] =
    if (apiKey.hash == apiKeyHash) {
      Right(apiKey)
    } else {
      Left(Response.failed(Error.UnauthorizedError))
    }

  private val baseEndpoint: BaseEndpoint[Unit, Unit] =
    endpoint
      .errorOut(jsonBody[Response.Failure])
      .tag("Blockflow")

  private val authEndpoint: AuthEndpoint[Unit, Unit] =
    baseEndpoint
      .in(auth.apiKey(header[ApiKey]("X-API-KEY")))
      .serverLogicForCurrent(apiKey => Future.successful(checkApiKey(apiKey)))

  val getBlockflow: BaseEndpoint[TimeInterval, FetchResponse] =
    baseEndpoint.get
      .in("blockflow")
      .in(timeIntervalQuery)
      .out(jsonBody[FetchResponse])

  val getBlock: BaseEndpoint[Hash, BlockEntry] =
    baseEndpoint.get
      .in("blocks")
      .in(path[Hash]("block_hash"))
      .out(jsonBody[BlockEntry])
      .description("Get a block with hash")

  val getBalance: BaseEndpoint[Address, Balance] =
    baseEndpoint.get
      .in("addresses")
      .in(path[Address]("address"))
      .in("balance")
      .out(jsonBody[Balance])
      .description("Get the balance of a address")

  val getGroup: BaseEndpoint[Address, Group] =
    baseEndpoint.get
      .in("addresses")
      .in(path[Address]("address"))
      .in("group")
      .out(jsonBody[Group])
      .description("Get the group of a address")

  //have to be lazy to let `groupConfig` being initialized
  lazy val getHashesAtHeight: BaseEndpoint[(GroupIndex, GroupIndex, Int), HashesAtHeight] =
    baseEndpoint.get
      .in("hashes")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .in(query[Int]("height"))
      .out(jsonBody[HashesAtHeight])

  //have to be lazy to let `groupConfig` being initialized
  lazy val getChainInfo: BaseEndpoint[(GroupIndex, GroupIndex), ChainInfo] =
    baseEndpoint.get
      .in("chains")
      .in(query[GroupIndex]("fromGroup"))
      .in(query[GroupIndex]("toGroup"))
      .out(jsonBody[ChainInfo])

  val createTransaction: BaseEndpoint[(PublicKey, Address, U256), CreateTransactionResult] =
    baseEndpoint.get
      .in("unsigned-transactions")
      .in(query[PublicKey]("fromKey"))
      .in(query[Address]("toAddress"))
      .in(query[U256]("value"))
      .out(jsonBody[CreateTransactionResult])
      .description("Create an unsigned transaction")

  val sendTransaction: AuthEndpoint[SendTransaction, TxResult] =
    authEndpoint.post
      .in("transactions")
      .in(jsonBody[SendTransaction])
      .out(jsonBody[TxResult])
      .description("Send a signed transaction")

  val minerAction: AuthEndpoint[MinerAction, Boolean] =
    authEndpoint.post
      .in("miners")
      .in(query[MinerAction]("action"))
      .out(jsonBody[Boolean])
      .description("Execute an action on miners")
}
