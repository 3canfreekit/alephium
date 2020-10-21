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

package org.alephium.protocol.vm.lang

import org.alephium.protocol.vm.Val
import org.alephium.util.AVector

sealed trait Type {
  def toVal: Val.Type
}

object Type {
  val primitives: AVector[Type] = AVector[Type](Bool, Byte, I256, U256) ++
    AVector[Type](BoolVec, ByteVec, I256Vec, U256Vec)

  def fromVal(tpe: Val.Type): Type = {
    tpe match {
      case Val.Bool    => Bool
      case Val.Byte    => Byte
      case Val.I256    => I256
      case Val.U256    => U256
      case Val.BoolVec => BoolVec
      case Val.ByteVec => ByteVec
      case Val.I256Vec => I256Vec
      case Val.U256Vec => U256Vec
      case Val.Address => Address
    }
  }

  case object Bool    extends Type { def toVal: Val.Type = Val.Bool }
  case object Byte    extends Type { def toVal: Val.Type = Val.Byte }
  case object I256    extends Type { def toVal: Val.Type = Val.I256 }
  case object U256    extends Type { def toVal: Val.Type = Val.U256 }
  case object BoolVec extends Type { def toVal: Val.Type = Val.BoolVec }
  case object ByteVec extends Type { def toVal: Val.Type = Val.ByteVec }
  case object I256Vec extends Type { def toVal: Val.Type = Val.I256Vec }
  case object U256Vec extends Type { def toVal: Val.Type = Val.U256Vec }
  case object Address extends Type { def toVal: Val.Type = Val.Address }

  sealed trait Contract extends Type {
    def id: Ast.TypeId
    def toVal: Val.Type = Val.ByteVec

    override def hashCode(): Int = id.hashCode()

    override def equals(obj: Any): Boolean = obj match {
      case that: Contract => this.id == that.id
      case _              => false
    }
  }
  object Contract {
    def local(id: Ast.TypeId, variable: Ast.Ident): LocalVar   = new LocalVar(id, variable)
    def global(id: Ast.TypeId, variable: Ast.Ident): GlobalVar = new GlobalVar(id, variable)
    def stack(id: Ast.TypeId): Stack                           = new Stack(id)

    final class LocalVar(val id: Ast.TypeId, val variable: Ast.Ident)  extends Contract
    final class GlobalVar(val id: Ast.TypeId, val variable: Ast.Ident) extends Contract
    final class Stack(val id: Ast.TypeId)                              extends Contract
  }
}
