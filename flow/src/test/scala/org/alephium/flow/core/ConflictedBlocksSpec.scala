package org.alephium.flow.core

import org.scalacheck.Gen

import org.alephium.protocol.Signature
import org.alephium.protocol.config.GroupConfigFixture
import org.alephium.protocol.model._
import org.alephium.util.{AlephiumSpec, AVector, Duration, Random}

class ConflictedBlocksSpec extends AlephiumSpec with TxInputGenerators with GroupConfigFixture {
  val groups   = 3
  val txInputs = Gen.listOfN(10, txInputGen).sample.get.toIndexedSeq

  trait Fixture {
    def blockGen0(txInputs: TxInput*): Block = {
      val transaction =
        Transaction.from(AVector.from(txInputs),
                         AVector.empty[AssetOutput],
                         AVector.empty[Signature])
      Block.from(AVector.empty, AVector(transaction), Target.Max, Random.nextNonZeroInt())
    }

    def blockGen1(txInputs: AVector[TxInput]*): Block = {
      val transactions = txInputs.map(inputs =>
        Transaction.from(inputs, AVector.empty[AssetOutput], AVector.empty[Signature]))
      Block.from(AVector.empty, AVector.from(transactions), Target.Max, Random.nextNonZeroInt())
    }

    val cache = ConflictedBlocks.emptyCache(Duration.ofMinutesUnsafe(10))
  }

  it should "add block into cache" in new Fixture {
    val block = blockGen1(AVector(txInputs(0), txInputs(1)), AVector(txInputs(2), txInputs(3)))
    cache.add(block)
    cache.isBlockCached(block) is true
    cache.isBlockCached(block.hash) is true
    cache.txCache.size is 4
    cache.conflictedBlocks.size is 0

    cache.add(block) // add the block again
    cache.isBlockCached(block) is true
    cache.isBlockCached(block.hash) is true
    cache.txCache.size is 4
    cache.conflictedBlocks.size is 0
  }

  it should "remove caches" in new Fixture {
    val block = blockGen1(AVector(txInputs(0), txInputs(1)), AVector(txInputs(2), txInputs(3)))
    cache.add(block)
    cache.remove(block)

    cache.isBlockCached(block) is false
    cache.txCache.size is 0
    cache.conflictedBlocks.size is 0
  }

  trait Fixture1 extends Fixture {
    val block0 = blockGen0(txInputs(0), txInputs(1))
    val block1 = blockGen0(txInputs(1), txInputs(2))
    val block2 = blockGen0(txInputs(2), txInputs(3))
    val blocks = Seq(block0, block1, block2)
  }

  it should "detect conflicts" in new Fixture1 {
    blocks.foreach(cache.add)
    blocks.foreach(block => cache.isBlockCached(block) is true)
    cache.txCache.size is 4
    cache.txCache(txInputs(0).outputRef).toSet is Set(block0.hash)
    cache.txCache(txInputs(1).outputRef).toSet is Set(block0.hash, block1.hash)
    cache.txCache(txInputs(2).outputRef).toSet is Set(block1.hash, block2.hash)
    cache.txCache(txInputs(3).outputRef).toSet is Set(block2.hash)
    cache.conflictedBlocks.size is 3
    cache.conflictedBlocks(block0.hash).toSet is Set(block1.hash)
    cache.conflictedBlocks(block1.hash).toSet is Set(block0.hash, block2.hash)
    cache.conflictedBlocks(block2.hash).toSet is Set(block1.hash)
  }

  it should "add, remove blocks properly 0" in new Fixture1 {
    blocks.foreach(cache.add)
    cache.remove(block0)
    cache.isBlockCached(block0) is false
    cache.txCache.size is 3
    cache.conflictedBlocks.size is 2
    cache.remove(block1)
    cache.isBlockCached(block1) is false
    cache.txCache.size is 2
    cache.conflictedBlocks.size is 0
    cache.remove(block2)
    blocks.foreach(block => cache.isBlockCached(block) is false)
    cache.txCache.size is 0
    cache.conflictedBlocks.size is 0
  }

  it should "add, remove blocks properly 1" in new Fixture1 {
    blocks.foreach(cache.add)
    cache.remove(block1)
    cache.isBlockCached(block1) is false
    cache.txCache.size is 4
    cache.conflictedBlocks.size is 0
    cache.remove(block0)
    cache.isBlockCached(block0) is false
    cache.txCache.size is 2
    cache.conflictedBlocks.size is 0
    cache.remove(block2)
    blocks.foreach(block => cache.isBlockCached(block) is false)
    cache.txCache.size is 0
    cache.conflictedBlocks.size is 0
  }

  it should "cache nothing when keep duration is 0" in new Fixture1 {
    val cache0 = ConflictedBlocks.emptyCache(Duration.ofMinutesUnsafe(0))
    blocks.foreach(cache0.add)
    blocks.foreach(block => cache0.isBlockCached(block) is false)
  }
}
