package org.alephium.flow.storage

import org.alephium.crypto.Keccak256
import org.alephium.util.AVector

trait BlockHashPool {
  def numHashes: Int

  def maxWeight: Int

  def maxHeight: Int

  def contains(hash: Keccak256): Boolean

  def getWeight(hash: Keccak256): Int

  def getHeight(hash: Keccak256): Int

  def isTip(hash: Keccak256): Boolean

  def getBlockHashSlice(hash: Keccak256): AVector[Keccak256]

  def getBestTip: Keccak256

  def getAllTips: AVector[Keccak256]

  def getAllBlockHashes: Iterable[Keccak256]
}
