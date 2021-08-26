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

package org.alephium.flow.setting

import java.net.InetSocketAddress

import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters._

import com.typesafe.config.{ConfigException, ConfigFactory}
import com.typesafe.config.ConfigValueFactory
import net.ceedubs.ficus.Ficus._

import org.alephium.conf._
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex, NetworkId}
import org.alephium.util.{AlephiumSpec, AVector, Duration}

class AlephiumConfigSpec extends AlephiumSpec {
  it should "load alephium config" in new AlephiumConfigFixture {
    override val configValues: Map[String, Any] = Map(
      ("alephium.broker.groups", "13"),
      ("alephium.consensus.block-target-time", "11 seconds")
    )

    config.broker.groups is 13
    config.network.networkId is NetworkId(2)
    config.consensus.blockTargetTime is Duration.ofSecondsUnsafe(11)
    config.network.connectionBufferCapacityInByte is 100000000L
  }

  it should "load bootstrap config" in {

    case class Bootstrap(addresses: ArraySeq[InetSocketAddress])

    val expected =
      ArraySeq(new InetSocketAddress("127.0.0.1", 1234), new InetSocketAddress("127.0.0.1", 4321))

    ConfigFactory
      .parseString("""{ addresses = ["127.0.0.1:1234", "127.0.0.1:4321"] }""")
      .as[ArraySeq[InetSocketAddress]]("addresses")(inetSocketAddressesReader) is expected

    ConfigFactory
      .parseString("""{ addresses = "127.0.0.1:1234,127.0.0.1:4321" }""")
      .as[ArraySeq[InetSocketAddress]]("addresses")(inetSocketAddressesReader) is expected

    ConfigFactory
      .parseString("""{ addresses = "" }""")
      .as[ArraySeq[InetSocketAddress]]("addresses")(
        inetSocketAddressesReader
      ) is ArraySeq.empty
  }

  it should "fail to load if miner's addresses are wrong" in new AlephiumConfigFixture {
    val minerAddresses = AVector(
      "49bUQbTo6tHa35U3QC1tsAkEDaryyQGJD2S8eomYfcZx",
      "D9PBcRXK5uzrNYokNMB7oh6JpW86sZajJ5gD845cshED"
    )

    override val configValues: Map[String, Any] = Map(
      (
        "alephium.mining.miner-addresses",
        ConfigValueFactory.fromIterable(minerAddresses.toSeq.asJava)
      )
    )

    assertThrows[ConfigException](AlephiumConfig.load(newConfig, "alephium"))
  }

  class MinerFixture(groupIndexes: Seq[Int]) extends AlephiumConfigFixture {
    val groupConfig1 = new GroupConfig {
      override def groups: Int = 3
    }
    val minerAddresses = groupIndexes.map { g =>
      val groupIndex  = GroupIndex.unsafe(g)(groupConfig1)
      val (_, pubKey) = groupIndex.generateKey(groupConfig1)
      Address.p2pkh(pubKey).toBase58
    }

    override val configValues: Map[String, Any] = Map(
      (
        "alephium.mining.miner-addresses",
        ConfigValueFactory.fromIterable(minerAddresses.toSeq.asJava)
      )
    )
  }

  it should "fail to load if miner's addresses are of wrong indexes" in new MinerFixture(
    Seq(0, 1, 1)
  ) {
    assertThrows[ConfigException](AlephiumConfig.load(newConfig, "alephium"))
  }

  it should "load if miner's addresses are of correct indexes" in new MinerFixture(
    Seq(0, 1, 2)
  ) {
    config.mining.minerAddresses.get.toSeq is minerAddresses.map(str => Address.asset(str).get)
  }
}
