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

package org.alephium.flow.mempool

import org.alephium.flow.core.FlowUtils.AssetOutputInfo
import org.alephium.flow.setting.MemPoolSetting
import org.alephium.io.IOResult
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm.{LockupScript, WorldState}
import org.alephium.util.AVector

/*
 * MemPool is the class to store all the pending transactions
 *
 * Transactions should be ordered according to weights. The weight is calculated based on fees
 */
class MemPool private (
    group: GroupIndex,
    pools: AVector[TxPool],
    val txIndexes: TxIndexes,
    val pendingPool: PendingPool
)(implicit
    groupConfig: GroupConfig
) {
  def getPool(index: ChainIndex): TxPool = {
    assume(group == index.from)
    pools(index.to.value)
  }

  def size: Int = pools.sumBy(_.size)

  def contains(index: ChainIndex, transaction: TransactionTemplate): Boolean = {
    contains(index, transaction.id)
  }

  def contains(index: ChainIndex, txId: Hash): Boolean =
    getPool(index).contains(txId) || pendingPool.contains(txId)

  def collectForBlock(index: ChainIndex, maxNum: Int): AVector[TransactionTemplate] =
    getPool(index).collectForBlock(maxNum)

  def getAll(index: ChainIndex): AVector[TransactionTemplate] =
    getPool(index).getAll() ++ pendingPool.getAll()

  def isSpent(outputRef: AssetOutputRef): Boolean = {
    pendingPool.indexes.isSpent(outputRef) || txIndexes.isSpent(outputRef)
  }

  def isUnspentInPool(outputRef: AssetOutputRef): Boolean = {
    (txIndexes.outputIndex
      .contains(outputRef) || pendingPool.indexes.outputIndex.contains(outputRef)) &&
    (!txIndexes.inputIndex
      .contains(outputRef) && !pendingPool.indexes.inputIndex.contains(outputRef))
  }

  def isDoubleSpending(index: ChainIndex, tx: TransactionTemplate): Boolean = {
    assume(index.from == group)
    tx.unsigned.inputs.exists(input => isSpent(input.outputRef))
  }

  def addNewTx(index: ChainIndex, tx: TransactionTemplate): MemPool.NewTxCategory = {
    if (tx.unsigned.inputs.exists(input => isUnspentInPool(input.outputRef))) {
      pendingPool.add(tx)
      MemPool.AddedToLocalPool
    } else {
      addToTxPool(index, AVector(tx))
      MemPool.AddedToSharedPool
    }
  }

  def addToTxPool(index: ChainIndex, transactions: AVector[TransactionTemplate]): Int = {
    val count = getPool(index).add(transactions)
    transactions.foreach(txIndexes.add)
    count
  }

  def removeFromTxPool(index: ChainIndex, transactions: AVector[TransactionTemplate]): Int = {
    val count = getPool(index).remove(transactions)
    transactions.foreach(txIndexes.remove)
    count
  }

  // Note: we lock the mem pool so that we could update all the transaction pools
  def reorg(
      toRemove: AVector[AVector[Transaction]],
      toAdd: AVector[AVector[Transaction]]
  ): (Int, Int) = {
    assume(toRemove.length == groupConfig.groups && toAdd.length == groupConfig.groups)

    // First, add transactions from short chains, then remove transactions from canonical chains
    val added =
      toAdd.foldWithIndex(0)((sum, txs, toGroup) => sum + pools(toGroup).add(txs.map(_.toTemplate)))
    val removed = toRemove.foldWithIndex(0)((sum, txs, toGroup) =>
      sum + pools(toGroup).remove(txs.map(_.toTemplate))
    )
    (removed, added)
  }

  def getRelevantUtxos(
      lockupScript: LockupScript,
      utxosInBlock: AVector[AssetOutputInfo]
  ): AVector[AssetOutputInfo] = {
    val newUtxos =
      txIndexes.getRelevantUtxos(lockupScript) ++ pendingPool.getRelevantUtxos(lockupScript)

    (utxosInBlock ++ newUtxos).filterNot(asset =>
      txIndexes.isSpent(asset) || pendingPool.indexes.isSpent(asset)
    )
  }

  def updatePendingPool(
      worldState: WorldState.Persisted
  ): IOResult[AVector[TransactionTemplate]] = {
    pendingPool.extractReadyTxs(worldState).map { txs =>
      txs.groupBy(_.chainIndex).foreach { case (chainIndex, txss) =>
        addToTxPool(chainIndex, txss)
      }
      pendingPool.remove(txs)
      txs
    }
  }

  def getUtxo(outputRef: TxOutputRef): Option[TxOutput] = outputRef match {
    case ref: AssetOutputRef => getUtxo(ref)
    case _                   => None
  }

  def getUtxo(outputRef: AssetOutputRef): Option[TxOutput] = {
    val result = pendingPool.getUtxo(outputRef).flatMap {
      case Some(output) => Right(Some(output))
      case None         => txIndexes.getUtxo(outputRef)
    }
    result match {
      case Left(_)      => None // utxo is spent already
      case Right(value) => value
    }
  }

  def clear(): Unit = {
    pools.foreach(_.clear())
  }
}

object MemPool {
  def empty(
      groupIndex: GroupIndex
  )(implicit groupConfig: GroupConfig, memPoolSetting: MemPoolSetting): MemPool = {
    val pools = AVector.fill(groupConfig.groups)(TxPool.empty(memPoolSetting.txPoolCapacity))
    new MemPool(groupIndex, pools, TxIndexes.emptySharedPool, PendingPool.empty)
  }

  sealed trait NewTxCategory
  case object AddedToLocalPool  extends NewTxCategory
  case object AddedToSharedPool extends NewTxCategory
}
