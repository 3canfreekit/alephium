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

import scala.jdk.CollectionConverters._

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import org.alephium.protocol.{PrivateKey, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.GroupIndex
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, Env, U256}

trait AlephiumConfigFixture {
  val configValues: Map[String, Any] = Map.empty

  val genesisBalance: U256 = U256.unsafe(100)

  val env      = Env.resolve()
  val rootPath = Platform.getRootPath(env)

  lazy val newConfig = ConfigFactory
    .parseMap(configValues.view.mapValues(ConfigValueFactory.fromAnyRef).toMap.asJava)
    .withFallback(Configs.parseConfig(rootPath, None))

  lazy val groups0 = newConfig.getInt("alephium.broker.groups")

  lazy val groupConfig: GroupConfig = new GroupConfig { override def groups: Int = groups0 }

  lazy val genesisKeys =
    AVector.tabulate[(PrivateKey, PublicKey, U256)](groups0) { i =>
      val groupIndex              = GroupIndex.unsafe(i)(groupConfig)
      val (privateKey, publicKey) = groupIndex.generateKey(groupConfig)
      (privateKey, publicKey, genesisBalance)
    }

  implicit lazy val config = {
    val tmp = AlephiumConfig
      .load(newConfig.getConfig("alephium"))
      .toOption
      .get

    val newChains =
      tmp.chains.copy(genesisBalances = genesisKeys.map(p => (LockupScript.p2pkh(p._2), p._3)))
    tmp.copy(chains = newChains)
  }

  implicit lazy val brokerConfig     = config.broker
  implicit lazy val consensusConfig  = config.consensus
  implicit lazy val networkSetting   = config.network
  implicit lazy val discoverySetting = config.discovery
  implicit lazy val memPoolSetting   = config.mempool
  implicit lazy val miningSetting    = config.mining
}
