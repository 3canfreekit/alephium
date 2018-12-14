package org.alephium.flow.model

import org.alephium.crypto.Keccak256
import org.alephium.flow.constant.Network

/*
 * There are 2 * groups - 1 dependent hashes for each block
 * The first groups - 1 hashes are for the other groups
 * The last groups hashes are for the chains related to the target group
 */
case class BlockDeps(chainIndex: ChainIndex, deps: Seq[Keccak256]) {

  def getChainHash: Keccak256 = {
    deps.view.takeRight(Network.groups)(chainIndex.to)
  }
}
