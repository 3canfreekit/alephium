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

package org.alephium.protocol.vm

import org.alephium.protocol
import org.alephium.protocol.Hash
import org.alephium.protocol.model._
import org.alephium.util.AVector

trait ContextGenerators extends VMFactory with NoIndexModelGenerators {
  def prepareContract(contract: StatefulContract,
                      fields: AVector[Val],
                      gasLimit: Int = minimalGas): (StatefulContractObject, StatefulContext) = {
    val groupIndex        = GroupIndex.unsafe(0)
    val contractOutputRef = contractOutputRefGen(groupIndex).sample.get
    val contractOutput    = contractOutputGen(groupIndex)().sample.get
    val worldStateNew =
      cachedWorldState
        .createContract(contract, fields, contractOutputRef, contractOutput)
        .toOption
        .get
    val obj = contract.toObject(contractOutputRef.key, fields)
    val context = new StatefulContext {
      override var worldState: WorldState                        = worldStateNew
      override def outputBalances: Frame.Balances                = ???
      override def nextOutputIndex: Int                          = ???
      override def txHash: Hash                                  = Hash.zero
      override def signatures: Stack[protocol.Signature]         = Stack.ofCapacity(0)
      override def getInitialBalances: ExeResult[Frame.Balances] = Left(NonPayableFrame)
      override var gasRemaining: Int                             = gasLimit
    }
    obj -> context
  }
}
