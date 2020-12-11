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

package org.alephium.flow.core

import scala.annotation.tailrec

import com.typesafe.scalalogging.StrictLogging

import org.alephium.flow.Utils
import org.alephium.flow.io.Storages
import org.alephium.flow.model.BlockDeps
import org.alephium.flow.setting.{AlephiumConfig, ConsensusSetting, MemPoolSetting}
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.{ALF, BlockHash}
import org.alephium.protocol.config.{BrokerConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.protocol.vm.WorldState
import org.alephium.util.{AVector, TimeStamp}

trait BlockFlow
    extends MultiChain
    with BlockFlowState
    with FlowUtils
    with ConflictedBlocks
    with BlockFlowValidation {
  def add(block: Block, weight: BigInt): IOResult[Unit] = ???

  def add(header: BlockHeader, weight: BigInt): IOResult[Unit] = ???

  override protected def getSyncLocatorsUnsafe(): AVector[AVector[BlockHash]] = {
    getSyncLocatorsUnsafe(brokerConfig)
  }

  private def getSyncLocatorsUnsafe(
      peerBrokerInfo: BrokerGroupInfo): AVector[AVector[BlockHash]] = {
    val (groupFrom, groupUntil) = brokerConfig.calIntersection(peerBrokerInfo)
    AVector.tabulate((groupUntil - groupFrom) * groups) { index =>
      val offset    = index / groups
      val fromGroup = groupFrom + offset
      val toGroup   = index % groups
      getSyncLocatorsUnsafe(ChainIndex.unsafe(fromGroup, toGroup))
    }
  }

  private def getSyncLocatorsUnsafe(chainIndex: ChainIndex): AVector[BlockHash] = {
    if (brokerConfig.contains(chainIndex.from)) {
      val chain = getHeaderChain(chainIndex)
      HistoryLocators
        .sampleHeights(ALF.GenesisHeight, chain.maxHeightUnsafe)
        .map(height => Utils.unsafe(chain.getHashes(height).map(_.head)))
    } else {
      AVector.empty
    }
  }

  override protected def getSyncInventoriesUnsafe(
      locators: AVector[AVector[BlockHash]]): AVector[AVector[BlockHash]] = {
    locators.map { locatorsPerChain =>
      if (locatorsPerChain.isEmpty) {
        AVector.empty[BlockHash]
      } else {
        val chainIndex = ChainIndex.from(locatorsPerChain.head)
        val chain      = getBlockChain(chainIndex)
        chain.getSyncDataUnsafe(locatorsPerChain)
      }
    }
  }

  override protected def getIntraSyncInventoriesUnsafe(
      remoteBroker: BrokerGroupInfo): AVector[AVector[BlockHash]] = {
    AVector.tabulate(brokerConfig.groupNumPerBroker * remoteBroker.groupNumPerBroker) { index =>
      val k         = index / remoteBroker.groupNumPerBroker
      val l         = index % remoteBroker.groupNumPerBroker
      val fromGroup = brokerConfig.groupFrom + k
      val toGroup   = remoteBroker.groupFrom + l
      val chain     = getBlockChain(GroupIndex.unsafe(fromGroup), GroupIndex.unsafe(toGroup))
      chain.getLatestHashesUnsafe()
    }
  }

  // data should be valid, and parent should be in blockflow already
  def isRecent(data: FlowData): IOResult[Boolean] = {
    if (data.timestamp > TimeStamp.now().minusUnsafe(consensusConfig.recentBlockTimestampDiff)) {
      Right(true)
    } else {
      val hashChain = getHashChain(data.hash)
      hashChain.isRecent(data.parentHash)
    }
  }
}

object BlockFlow extends StrictLogging {
  type WorldStateUpdater = (WorldState.Cached, Block) => IOResult[Unit]

  def fromGenesisUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromGenesisUnsafe(storages, config.genesisBlocks)(config.broker,
                                                      config.consensus,
                                                      config.mempool)
  }

  def fromGenesisUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting,
      memPoolSetting: MemPoolSetting): BlockFlow = {
    logger.info(s"Initialize storage for BlockFlow")
    new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromGenesisUnsafe(storages),
      BlockChain.fromGenesisUnsafe(storages),
      BlockHeaderChain.fromGenesisUnsafe(storages)
    )
  }

  def fromStorageUnsafe(config: AlephiumConfig, storages: Storages): BlockFlow = {
    fromStorageUnsafe(storages, config.genesisBlocks)(config.broker,
                                                      config.consensus,
                                                      config.mempool)
  }

  def fromStorageUnsafe(storages: Storages, genesisBlocks: AVector[AVector[Block]])(
      implicit brokerConfig: BrokerConfig,
      consensusSetting: ConsensusSetting,
      memPoolSetting: MemPoolSetting): BlockFlow = {
    val blockflow = new BlockFlowImpl(
      genesisBlocks,
      BlockChainWithState.fromStorageUnsafe(storages),
      BlockChain.fromStorageUnsafe(storages),
      BlockHeaderChain.fromStorageUnsafe(storages)
    )
    logger.info(s"Load BlockFlow from storage: #${blockflow.numHashes} blocks/headers")
    blockflow.updateBestDepsAfterLoadingUnsafe()
    blockflow
  }

  class BlockFlowImpl(
      val genesisBlocks: AVector[AVector[Block]],
      val blockchainWithStateBuilder: (Block, BlockFlow.WorldStateUpdater) => BlockChainWithState,
      val blockchainBuilder: Block                                         => BlockChain,
      val blockheaderChainBuilder: BlockHeader                             => BlockHeaderChain
  )(implicit val brokerConfig: BrokerConfig,
    val consensusConfig: ConsensusSetting,
    val mempoolSetting: MemPoolSetting)
      extends BlockFlow {
    def add(block: Block): IOResult[Unit] = {
      val index = block.chainIndex
      assume(index.relateTo(brokerConfig))

      cacheBlock(block)
      if (brokerConfig.contains(block.chainIndex.from)) {
        cacheForConflicts(block)
      }
      for {
        weight <- calWeight(block)
        _      <- getBlockChain(index).add(block, weight)
        _      <- updateBestDeps()
      } yield ()
    }

    def add(header: BlockHeader): IOResult[Unit] = {
      val index = header.chainIndex
      assume(!index.relateTo(brokerConfig))

      for {
        weight <- calWeight(header)
        _      <- getHeaderChain(index).add(header, weight)
        _      <- updateBestDeps()
      } yield ()
    }

    private def calWeight(block: Block): IOResult[BigInt] = {
      calWeight(block.header)
    }

    private def calWeight(header: BlockHeader): IOResult[BigInt] = {
      IOUtils.tryExecute(calWeightUnsafe(header))
    }

    private def calWeightUnsafe(header: BlockHeader): BigInt = {
      if (header.isGenesis) {
        ALF.GenesisWeight * brokerConfig.depsNum
      } else {
        val weight1 = header.inDeps.sumBy(calGroupWeightUnsafe)
        val weight2 = header.outDeps.sumBy(getChainWeightUnsafe)
        weight1 + weight2 + header.target.value
      }
    }

    private def calWeightUnsafe(flowTips: FlowTips): BigInt = {
      val weight1 = flowTips.inTips.sumBy(calGroupWeightUnsafe)
      val weight2 = flowTips.outTips.sumBy(getChainWeightUnsafe)
      weight1 + weight2
    }

    private def calGroupWeightUnsafe(hash: BlockHash): BigInt = {
      val header = getBlockHeaderUnsafe(hash)
      if (header.isGenesis) {
        ALF.GenesisWeight
      } else {
        header.outDeps.sumBy(getChainWeightUnsafe) + header.target.value
      }
    }

    def getBestTipUnsafe: BlockHash = {
      aggregateHash(_.getBestTipUnsafe)(blockHashOrdering.max)
    }

    override def getAllTips: AVector[BlockHash] = {
      aggregateHash(_.getAllTips)(_ ++ _)
    }

    def tryExtendUnsafe(tipsCur: FlowTips,
                        weightCur: BigInt,
                        group: GroupIndex,
                        toTry: AVector[BlockHash],
                        bestTip: BlockHash): (FlowTips, BigInt) = {
      toTry
        .sorted(blockHashOrdering.reverse)
        .fold[(FlowTips, BigInt)](tipsCur -> weightCur) {
          case ((maxTips, maxWeight), tip) =>
            // only consider tips < bestTip
            if (blockHashOrdering.lt(tip, bestTip)) {
              tryMergeUnsafe(tipsCur, tip, group, checkTxConflicts = true) match {
                case Some(merged) =>
                  val weight = calWeightUnsafe(merged)
                  if (weight > maxWeight) (merged, weight) else (maxTips, maxWeight)
                case None => (maxTips, maxWeight)
              }
            } else {
              maxTips -> maxWeight
            }
        }
    }

    def calBestDepsUnsafe(group: GroupIndex): BlockDeps = {
      val bestTip    = getBestTipUnsafe
      val bestIndex  = ChainIndex.from(bestTip)
      val flowTips0  = getFlowTipsUnsafe(bestTip, group)
      val weight0    = calWeightUnsafe(flowTips0)
      val groupOrder = BlockFlow.randomGroupOrders(bestTip)

      val (flowTips1, weight1) =
        (if (bestIndex.from == group) groupOrder.filter(_ != bestIndex.to.value) else groupOrder)
          .fold(flowTips0 -> weight0) {
            case ((tipsCur, weightCur), _r) =>
              val r     = GroupIndex.unsafe(_r)
              val chain = getHashChain(group, r)
              tryExtendUnsafe(tipsCur, weightCur, group, chain.getAllTips, bestTip)
          }
      val (flowTips2, _) = groupOrder
        .filter(g => g != group.value && g != bestIndex.from.value)
        .fold(flowTips1 -> weight1) {
          case ((tipsCur, weightCur), _l) =>
            val l = GroupIndex.unsafe(_l)
            val toTry = (0 until groups).foldLeft(AVector.empty[BlockHash]) { (acc, _r) =>
              val r = GroupIndex.unsafe(_r)
              acc ++ getHashChain(l, r).getAllTips
            }
            tryExtendUnsafe(tipsCur, weightCur, group, toTry, bestTip)
        }
      flowTips2.toBlockDeps
    }

    def updateBestDepsUnsafe(): Unit =
      brokerConfig.groupFrom until brokerConfig.groupUntil foreach { mainGroup =>
        val deps = calBestDepsUnsafe(GroupIndex.unsafe(mainGroup))
        updateMemPoolUnsafe(mainGroup, deps)
        updateBestDeps(mainGroup, deps)
      }

    def updateBestDepsAfterLoadingUnsafe(): Unit =
      brokerConfig.groupFrom until brokerConfig.groupUntil foreach { mainGroup =>
        val deps = calBestDepsUnsafe(GroupIndex.unsafe(mainGroup))
        updateBestDeps(mainGroup, deps)
      }

    def updateBestDeps(): IOResult[Unit] = {
      IOUtils.tryExecute(updateBestDepsUnsafe())
    }
  }

  def randomGroupOrders(hash: BlockHash)(implicit config: GroupConfig): AVector[Int] = {
    val groupOrders = Array.tabulate(config.groups)(identity)

    @tailrec
    def shuffle(index: Int, seed: BlockHash): Unit = {
      if (index < groupOrders.length - 1) {
        val groupRemaining = groupOrders.length - index
        val randomIndex    = index + Math.floorMod(seed.toRandomIntUnsafe, groupRemaining)
        val tmp            = groupOrders(index)
        groupOrders(index)       = groupOrders(randomIndex)
        groupOrders(randomIndex) = tmp
        shuffle(index + 1, BlockHash.hash(seed.bytes))
      }
    }

    shuffle(0, hash)
    AVector.unsafe(groupOrders)
  }
}
