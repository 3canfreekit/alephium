package org.alephium.benchmark

import java.util.concurrent.TimeUnit

import org.alephium.flow.PlatformConfig
import org.alephium.protocol.model.{Block, ChainIndex}
import org.alephium.serde.RandomBytes
import org.alephium.util.AVector
import org.openjdk.jmh.annotations._

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
class MiningBench extends PlatformConfig.Default {

  @Benchmark
  def mineGenesis(): Boolean = {
    val nonce = RandomBytes.source.nextInt()
    val block = Block.genesis(AVector.empty, config.maxMiningTarget, BigInt(nonce))
    val i     = RandomBytes.source.nextInt(config.groups)
    val j     = RandomBytes.source.nextInt(config.groups)
    block.preValidate(ChainIndex(i, j))
  }
}
