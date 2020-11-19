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

package org.alephium.protocol.model

import scala.annotation.tailrec
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.ArrayBuffer

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.protocol.config.GroupConfig
import org.alephium.serde.Serde
import org.alephium.util.{AVector, TimeStamp, U256}

final case class Block(header: BlockHeader, transactions: AVector[Transaction])
    extends HashSerde[Block]
    with FlowData {
  override def hash: Hash = header.hash

  def coinbase: Transaction = transactions.last

  def coinbaseReward: U256 = coinbase.unsigned.fixedOutputs.head.amount

  // only use this after validation
  def gasFee: U256 = nonCoinbase.fold(U256.Zero)(_ addUnsafe _.gasFeeUnsafe)

  def nonCoinbase: AVector[Transaction] = transactions.init

  def nonCoinbaseLength: Int = transactions.length - 1

  override def timestamp: TimeStamp = header.timestamp

  override def target: Target = header.target

  def chainIndex(implicit config: GroupConfig): ChainIndex = {
    header.chainIndex
  }

  def isGenesis: Boolean = header.isGenesis

  def parentHash(implicit config: GroupConfig): Hash = {
    header.parentHash
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): Hash = {
    header.uncleHash(toIndex)
  }

  def getScriptExecutionOrder(implicit config: GroupConfig): AVector[Int] = {
    if (isGenesis) {
      AVector.empty
    } else {
      Block.getScriptExecutionOrder(parentHash, nonCoinbase)
    }
  }

  def getNonCoinbaseExecutionOrder(implicit config: GroupConfig): AVector[Int] = {
    assume(!isGenesis)
    Block.getNonCoinbaseExecutionOrder(parentHash, nonCoinbase)
  }
}

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.header, b.transactions))

  def from(blockDeps: AVector[Hash],
           transactions: AVector[Transaction],
           target: Target,
           timeStamp: TimeStamp,
           nonce: BigInt): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Hash.hash(transactions)
    val blockHeader = BlockHeader(blockDeps, txsHash, timeStamp, target, nonce)
    Block(blockHeader, transactions)
  }

  def genesis(transactions: AVector[Transaction], target: Target, nonce: BigInt): Block = {
    val txsHash     = Hash.hash(transactions)
    val blockHeader = BlockHeader.genesis(txsHash, target, nonce)
    Block(blockHeader, transactions)
  }

  def scriptIndexes[T <: TransactionAbstract](nonCoinbase: AVector[T]): ArrayBuffer[Int] = {
    val indexes = ArrayBuffer.empty[Int]
    nonCoinbase.foreachWithIndex {
      case (tx, txIndex) =>
        if (tx.unsigned.scriptOpt.nonEmpty) {
          indexes.addOne(txIndex)
        }
    }
    indexes
  }

  // we shuffle tx scripts randomly for execution to mitigate front-running
  def getScriptExecutionOrder[T <: TransactionAbstract](parentHash: Hash,
                                                        nonCoinbase: AVector[T]): AVector[Int] = {
    val nonCoinbaseLength = nonCoinbase.length
    val scriptOrders      = scriptIndexes(nonCoinbase)

    @tailrec
    def shuffle(index: Int, seed: Hash): Unit = {
      if (index < scriptOrders.length - 1) {
        val txRemaining = scriptOrders.length - index
        val randomIndex = index + Math.floorMod(seed.toRandomIntUnsafe, txRemaining)
        val tmp         = scriptOrders(index)
        scriptOrders(index)       = scriptOrders(randomIndex)
        scriptOrders(randomIndex) = tmp
        shuffle(index + 1, nonCoinbase(randomIndex).hash)
      }
    }

    if (scriptOrders.length > 1) {
      val initialSeed = {
        val maxIndex = nonCoinbaseLength - 1
        val samples  = ArraySeq(0, maxIndex / 2, maxIndex)
        samples.foldLeft(parentHash) {
          case (acc, index) =>
            val tx = nonCoinbase(index)
            Hash.xor(acc, tx.hash)
        }
      }
      shuffle(0, initialSeed)
    }
    AVector.from(scriptOrders)
  }

  def getNonCoinbaseExecutionOrder[T <: TransactionAbstract](
      parentHash: Hash,
      nonCoinbase: AVector[T]): AVector[Int] = {
    getScriptExecutionOrder(parentHash, nonCoinbase) ++ nonCoinbase.foldWithIndex(
      AVector.empty[Int]) {
      case (acc, tx, index) => if (tx.unsigned.scriptOpt.isEmpty) acc :+ index else acc
    }
  }
}
