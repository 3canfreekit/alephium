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

package org.alephium.flow.validation

import org.alephium.flow.core._
import org.alephium.protocol.Hash
import org.alephium.protocol.config.{BrokerConfig, ConsensusConfig, GroupConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Forest}

abstract class Validation[T <: FlowData, I <: InvalidStatus] {
  implicit def brokerConfig: BrokerConfig
  implicit def consensusConfig: ConsensusConfig

  def validate(data: T, flow: BlockFlow): ValidationResult[I, Unit]

  def validateUntilDependencies(data: T, flow: BlockFlow): ValidationResult[I, Unit]

  def validateAfterDependencies(data: T, flow: BlockFlow): ValidationResult[I, Unit]
}

object Validation {
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def validateFlowDAG[T <: FlowData](datas: AVector[T])(
      implicit config: GroupConfig): Option[AVector[Forest[Hash, T]]] = {
    val splits = datas.splitBy(_.chainIndex)
    val builds = splits.map(ds => Forest.tryBuild[Hash, T](ds, _.hash, _.parentHash))
    if (builds.forall(_.nonEmpty)) Some(builds.map(_.get)) else None
  }

  def validateMined[T <: FlowData](data: T, index: ChainIndex)(
      implicit config: GroupConfig): Boolean = {
    data.chainIndex == index && checkWorkAmount(data)
  }

  protected[validation] def checkWorkAmount[T <: FlowData](data: T): Boolean = {
    val current = BigInt(1, data.hash.bytes.toArray)
    current.compareTo(data.target.value) <= 0
  }
}
