package org.alephium.protocol.config

import org.alephium.util.Duration

trait ConsensusConfigFixture {
  implicit val config = new ConsensusConfig {
    override val groups: Int = 3

    override def numZerosAtLeastInHash: Int = 0
    override def maxMiningTarget: BigInt    = (BigInt(1) << 256) - 1
    override def blockTargetTime: Duration  = Duration.ofMinutesUnsafe(4)
    override def tipsPruneInterval: Int     = 2
  }
}
