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

package org.alephium.flow.network.sync

import akka.actor.Props
import akka.testkit.TestActorRef

import org.alephium.flow.AlephiumFlowActorSpec
import org.alephium.flow.core.BlockFlow
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.protocol.Hash
import org.alephium.protocol.model.ChainIndex
import org.alephium.util.AVector

class DownloadTrackerSpec extends AlephiumFlowActorSpec("DownloadTracker") {
  trait Fixture { F =>
    val blockflow = genesisBlockFlow()
    val hashes = AVector.from((1 to 5).map { k =>
      val block = transfer(blockflow, ChainIndex.unsafe(0, 0))
      addAndCheck(blockflow, block, k)
      block.hash
    })

    object TestDownloadTracker {
      def props(): Props = Props(new TestDownloadTracker())
    }

    class TestDownloadTracker extends DownloadTracker {
      override def blockflow: BlockFlow = F.blockflow

      override def receive: Receive = {
        case BlockFlowSynchronizer.SyncInventories(hashes) =>
          download(hashes)
        case BlockFlowSynchronizer.BlockFinalized(hash) =>
          finalized(hash)
      }
    }

    val downloadTrack = TestActorRef[TestDownloadTracker](TestDownloadTracker.props())

    val randomHashes = AVector.fill(5)(Hash.generate)
  }

  it should "track downloading" in new Fixture {
    hashes.foreach(hash => blockFlow.contains(hash) isE true)

    downloadTrack ! BlockFlowSynchronizer.SyncInventories(AVector(hashes))
    expectMsg(BrokerHandler.DownloadBlocks(AVector.empty[Hash]))
    downloadTrack.underlyingActor.downloading.isEmpty is true

    downloadTrack ! BlockFlowSynchronizer.SyncInventories(AVector(hashes ++ randomHashes))
    expectMsg(BrokerHandler.DownloadBlocks(randomHashes))
    downloadTrack.underlyingActor.downloading.toSet is randomHashes.toSet

    downloadTrack ! BlockFlowSynchronizer.SyncInventories(AVector(hashes ++ randomHashes))
    expectMsg(BrokerHandler.DownloadBlocks(AVector.empty[Hash]))
    downloadTrack.underlyingActor.downloading.toSet is randomHashes.toSet

    hashes.foreach(downloadTrack ! BlockFlowSynchronizer.BlockFinalized(_))
    downloadTrack.underlyingActor.downloading.toSet is randomHashes.toSet

    (hashes ++ randomHashes).foreach(downloadTrack ! BlockFlowSynchronizer.BlockFinalized(_))
    downloadTrack.underlyingActor.downloading.isEmpty is true
  }
}
