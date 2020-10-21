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

package org.alephium.flow.handler

import scala.collection.mutable

import akka.testkit.TestProbe

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.model.DataOrigin
import org.alephium.protocol.Hash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model.{Block, BrokerGroupInfo, NoIndexModelGeneratorsLike}
import org.alephium.util.{ActorRefT, AVector}

class FlowHandlerSpec extends AlephiumFlowActorSpec("FlowHandler") with NoIndexModelGeneratorsLike {
  import FlowHandler._

  def genPending(block: Block): PendingBlock = {
    genPending(block, mutable.HashSet.empty)
  }

  def genPending(missings: mutable.HashSet[Hash]): PendingBlock = {
    val block = blockGen.sample.get
    genPending(block, missings)
  }

  def genPending(block: Block, missings: mutable.HashSet[Hash]): PendingBlock = {
    PendingBlock(block,
                 missings,
                 DataOrigin.Local,
                 ActorRefT(TestProbe().ref),
                 ActorRefT(TestProbe().ref))
  }

  trait StateFix {
    val block = blockGen.sample.get
  }

  it should "add status" in {
    val state = new FlowHandlerState { override def statusSizeLimit: Int = 2 }
    state.pendingStatus.size is 0

    val pending0 = genPending(mutable.HashSet.empty[Hash])
    state.addStatus(pending0)
    state.pendingStatus.size is 1
    state.pendingStatus.head._2 is pending0
    state.pendingStatus.last._2 is pending0
    state.pendingHashes.size is 1
    state.pendingHashes.contains(pending0.hash) is true
    state.counter is 1

    val pending1 = genPending(mutable.HashSet.empty[Hash])
    state.addStatus(pending1)
    state.pendingStatus.size is 2
    state.pendingStatus.head._2 is pending0
    state.pendingStatus.last._2 is pending1
    state.pendingHashes.size is 2
    state.pendingHashes.contains(pending1.hash) is true
    state.counter is 2

    val pending2 = genPending(mutable.HashSet.empty[Hash])
    state.addStatus(pending2)
    state.pendingStatus.size is 2
    state.pendingStatus.head._2 is pending1
    state.pendingStatus.last._2 is pending2
    state.pendingHashes.size is 2
    state.pendingHashes.contains(pending2.hash) is true
    state.counter is 3
  }

  it should "update status" in {
    val state  = new FlowHandlerState { override def statusSizeLimit: Int = 3 }
    val block0 = blockGen.sample.get
    val block1 = blockGen.sample.get
    val block2 = blockGen.sample.get

    val pending0 = genPending(block0, mutable.HashSet(block1.hash, block2.hash))
    state.addStatus(pending0)
    state.pendingStatus.size is 1
    state.pendingStatus.head._2.missingDeps.size is 2
    state.pendingHashes.size is 1
    state.pendingHashes.contains(pending0.hash) is true
    state.counter is 1

    val readies1 = state.updateStatus(block1.hash)
    readies1.size is 0
    state.pendingStatus.size is 1
    state.pendingStatus.head._2.missingDeps.size is 1
    state.pendingHashes.size is 1
    state.pendingHashes.contains(pending0.hash) is true
    state.counter is 1

    val readies2 = state.updateStatus(block2.hash).toList
    readies2.size is 1
    readies2.head is pending0
    state.pendingStatus.size is 0
    state.pendingHashes.size is 0
    state.counter is 1
  }

  it should "not update duplicated pending" in {
    val state = new FlowHandlerState { override def statusSizeLimit: Int = 3 }
    val block = blockGen.sample.get

    val pending = genPending(block, mutable.HashSet.empty[Hash])
    state.addStatus(pending)
    state.pendingStatus.size is 1
    state.pendingHashes.size is 1
    state.pendingHashes.contains(pending.hash) is true
    state.counter is 1

    state.addStatus(pending)
    state.pendingStatus.size is 1
    state.pendingHashes.size is 1
    state.pendingHashes.contains(pending.hash) is true
    state.counter is 1
  }

  it should "calculate locators" in {
    val groupNum = 6

    val brokerGroupInfo0 = new BrokerConfig {
      override def groups: Int    = groupNum
      override def brokerId: Int  = 1
      override def brokerNum: Int = 2
    }
    val brokerGroupInfo1 = new BrokerGroupInfo {
      override def brokerId: Int          = 2
      override def groupNumPerBroker: Int = 2
    }
    val brokerGroupInfo2 = new BrokerGroupInfo {
      override def brokerId: Int          = 1
      override def groupNumPerBroker: Int = 2
    }
    val brokerGroupInfo3 = new BrokerGroupInfo {
      override def brokerId: Int          = 0
      override def groupNumPerBroker: Int = 2
    }

    val locators = AVector.tabulate(3 * 6) { k =>
      AVector.fill(k)(Hash.generate)
    }
    val flowEvent = FlowHandler.SyncLocators(brokerGroupInfo0, locators)
    flowEvent.filerFor(brokerGroupInfo0) is locators
    flowEvent.filerFor(brokerGroupInfo1) is locators.takeRight(2 * groupNum)
    flowEvent.filerFor(brokerGroupInfo2) is locators.take(1 * groupNum)
    flowEvent.filerFor(brokerGroupInfo3) is locators.take(0 * groupNum)
  }
}
