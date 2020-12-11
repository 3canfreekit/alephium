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

import org.alephium.flow.Utils
import org.alephium.io.IOResult
import org.alephium.protocol.BlockHash
import org.alephium.protocol.config.BrokerConfig
import org.alephium.protocol.model._
import org.alephium.util.{AVector, EitherF}

// scalastyle:off number.of.methods
trait FlowTipsUtil {
  implicit def brokerConfig: BrokerConfig

  def groups: Int
  def bestGenesisHashes: AVector[BlockHash]
  def genesisBlocks: AVector[AVector[Block]]

  def getBlockUnsafe(hash: BlockHash): Block
  def getBlockHeader(hash: BlockHash): IOResult[BlockHeader]
  def getBlockHeaderUnsafe(hash: BlockHash): BlockHeader
  def getHashChain(hash: BlockHash): BlockHashChain
  def getHashChain(chainIndex: ChainIndex): BlockHashChain

  def isConflicted(hashes: AVector[BlockHash], getBlock: BlockHash => Block): Boolean

  def getInTip(dep: BlockHash, currentGroup: GroupIndex): IOResult[BlockHash] = {
    getBlockHeader(dep).map { header =>
      val from = header.chainIndex.from
      if (header.isGenesis) {
        genesisBlocks(from.value)(currentGroup.value).hash
      } else {
        if (currentGroup == ChainIndex.from(dep).to) dep else header.uncleHash(currentGroup)
      }
    }
  }

  def getOutTips(header: BlockHeader, inclusive: Boolean): AVector[BlockHash] = {
    val index = header.chainIndex
    if (header.isGenesis) {
      genesisBlocks(index.from.value).map(_.hash)
    } else {
      if (inclusive) {
        header.outDeps.replace(index.to.value, header.hash)
      } else {
        header.outDeps
      }
    }
  }

  // if inclusive is true, the current header would be included
  def getInOutTips(header: BlockHeader,
                   currentGroup: GroupIndex,
                   inclusive: Boolean): IOResult[AVector[BlockHash]] = {
    if (header.isGenesis) {
      val inTips = AVector.tabulate(groups - 1) { i =>
        if (i < currentGroup.value) {
          genesisBlocks(i)(currentGroup.value).hash
        } else {
          genesisBlocks(i + 1)(currentGroup.value).hash
        }
      }
      val outTips = genesisBlocks(currentGroup.value).map(_.hash)
      Right(inTips ++ outTips)
    } else {
      val outTips = getOutTips(header, inclusive)
      header.inDeps.mapE(getInTip(_, currentGroup)).map(_ ++ outTips)
    }
  }

  def getInOutTips(hash: BlockHash,
                   currentGroup: GroupIndex,
                   inclusive: Boolean): IOResult[AVector[BlockHash]] = {
    getBlockHeader(hash).flatMap(getInOutTips(_, currentGroup, inclusive))
  }

  def getTipsDiff(newTip: BlockHash, oldTip: BlockHash): IOResult[AVector[BlockHash]] = {
    getHashChain(oldTip).getBlockHashesBetween(newTip, oldTip)
  }

  def getTipsDiffUnsafe(newTips: AVector[BlockHash],
                        oldTips: AVector[BlockHash]): AVector[BlockHash] = {
    Utils.unsafe(getTipsDiff(newTips, oldTips))
  }

  protected def getTipsDiff(newTips: AVector[BlockHash],
                            oldTips: AVector[BlockHash]): IOResult[AVector[BlockHash]] = {
    assume(newTips.length == oldTips.length)
    EitherF.foldTry(newTips.indices, AVector.empty[BlockHash]) { (acc, i) =>
      getTipsDiff(newTips(i), oldTips(i)).map(acc ++ _)
    }
  }

  private[core] def getFlowTipsUnsafe(tip: BlockHash, targetGroup: GroupIndex): FlowTips = {
    val header = getBlockHeaderUnsafe(tip)
    getFlowTipsUnsafe(header, targetGroup)
  }

  private[core] def getFlowTipsUnsafe(header: BlockHeader, targetGroup: GroupIndex): FlowTips = {
    val FlowTips.Light(inTips, targetTip) = getLightTipsUnsafe(header, targetGroup)
    val targetTips                        = getOutTipsUnsafe(targetTip, inclusive = true)

    FlowTips(targetGroup, inTips, targetTips)
  }

  private[core] def getLightTipsUnsafe(tip: BlockHash, targetGroup: GroupIndex): FlowTips.Light = {
    val header = getBlockHeaderUnsafe(tip)
    getLightTipsUnsafe(header, targetGroup)
  }

  private[core] def getLightTipsUnsafe(header: BlockHeader,
                                       targetGroup: GroupIndex): FlowTips.Light = {
    if (header.isGenesis) {
      val inTips = AVector.tabulate(groups - 1) { i =>
        val g = if (i < targetGroup.value) i else i + 1
        bestGenesisHashes(g)
      }
      val targetTip = bestGenesisHashes(targetGroup.value)
      FlowTips.Light(inTips, targetTip)
    } else {
      val inTips = AVector.tabulate(groups - 1) { i =>
        val g = if (i < targetGroup.value) i else i + 1
        getInTip(header, GroupIndex.unsafe(g))
      }
      val targetTip = getInTip(header, targetGroup)
      FlowTips.Light(inTips, targetTip)
    }
  }

  private[core] def getOutTipsUnsafe(tip: BlockHash,
                                     targetGroup: GroupIndex,
                                     inclusive: Boolean): AVector[BlockHash] = {
    val header = getBlockHeaderUnsafe(tip)
    getOutTipsUnsafe(header, targetGroup, inclusive)
  }

  private[core] def getOutTipsUnsafe(tip: BlockHash, inclusive: Boolean): AVector[BlockHash] = {
    val header = getBlockHeaderUnsafe(tip)
    getOutTips(header, inclusive)
  }

  private[core] def getOutTipsUnsafe(header: BlockHeader,
                                     targetGroup: GroupIndex,
                                     inclusive: Boolean): AVector[BlockHash] = {
    val index = header.chainIndex
    if (index.from == targetGroup) {
      getOutTips(header, inclusive)
    } else {
      getOutTipsUnsafe(getInTip(header, targetGroup), inclusive)
    }
  }

  private[core] def getInTip(header: BlockHeader, targetGroup: GroupIndex): BlockHash = {
    val from = header.chainIndex.from
    if (targetGroup.value < from.value) {
      header.blockDeps(targetGroup.value)
    } else if (targetGroup.value > from.value) {
      header.blockDeps(targetGroup.value - 1)
    } else {
      header.hash
    }
  }

  private[core] def tryMergeUnsafe(flowTips: FlowTips,
                                   tip: BlockHash,
                                   targetGroup: GroupIndex,
                                   checkTxConflicts: Boolean): Option[FlowTips] = {
    tryMergeUnsafe(flowTips, getLightTipsUnsafe(tip, targetGroup), checkTxConflicts)
  }

  private[core] def tryMergeUnsafe(flowTips: FlowTips,
                                   newTips: FlowTips.Light,
                                   checkTxConflicts: Boolean): Option[FlowTips] = {
    for {
      newInTips  <- mergeInTips(flowTips.inTips, newTips.inTips)
      newOutTips <- mergeOutTips(flowTips.outTips, newTips.outTip, checkTxConflicts)
    } yield FlowTips(flowTips.targetGroup, newInTips, newOutTips)
  }

  private[core] def merge(tip1: BlockHash, tip2: BlockHash): Option[BlockHash] = {
    if (isExtendingUnsafe(tip1, tip2)) {
      Some(tip1)
    } else if (isExtendingUnsafe(tip2, tip1)) {
      Some(tip2)
    } else {
      None
    }
  }

  private[core] def mergeTips(tips1: AVector[BlockHash],
                              tips2: AVector[BlockHash]): Option[AVector[BlockHash]] = {
    assume(tips1.length == tips2.length)

    @tailrec
    def iter(acc: AVector[BlockHash], g: Int): Option[AVector[BlockHash]] = {
      if (g == tips1.length) {
        Some(acc)
      } else {
        merge(tips1(g), tips2(g)) match {
          case Some(merged) => iter(acc :+ merged, g + 1)
          case None         => None
        }
      }
    }

    iter(AVector.ofSize(tips1.length), 0)
  }

  private[core] def mergeInTips(inTips1: AVector[BlockHash],
                                inTips2: AVector[BlockHash]): Option[AVector[BlockHash]] = {
    mergeTips(inTips1, inTips2)
  }

  private[core] def mergeOutTips(outTips: AVector[BlockHash],
                                 newTip: BlockHash,
                                 checkTxConflicts: Boolean): Option[AVector[BlockHash]] = {
    assume(outTips.length == groups)
    val newOutTips = getOutTipsUnsafe(newTip, inclusive = true)

    if (checkTxConflicts) {
      mergeTips(outTips, newOutTips).flatMap { mergedDeps =>
        val diffs   = getTipsDiffUnsafe(mergedDeps, outTips)
        val toCheck = (newTip +: diffs) ++ outTips
        Option.when(diffs.isEmpty || (!isConflicted(toCheck, getBlockUnsafe)))(mergedDeps)
      }
    } else {
      mergeTips(outTips, newOutTips)
    }
  }

  private[core] def isExtendingUnsafe(current: BlockHash, previous: BlockHash): Boolean = {
    val index1 = ChainIndex.from(current)
    val index2 = ChainIndex.from(previous)
    assume(index1.from == index2.from)

    val chain = getHashChain(index2)
    if (index1.to == index2.to) {
      Utils.unsafe(chain.isBefore(previous, current))
    } else {
      val groupDeps = getOutTipsUnsafe(current, index1.from, inclusive = true)
      Utils.unsafe(chain.isBefore(previous, groupDeps(index2.to.value)))
    }
  }
}
// scalastyle:on
