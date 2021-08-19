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

import scala.annotation.switch

import akka.util.ByteString

import org.alephium.crypto
import org.alephium.macros.ByteCode
import org.alephium.protocol.{Hash, PublicKey, SignatureSchema}
import org.alephium.protocol.model.AssetOutput
import org.alephium.serde.{deserialize => decode, serialize => encode, _}
import org.alephium.util
import org.alephium.util.{AVector, Bytes, Duration, TimeStamp}

// scalastyle:off file.size.limit number.of.types

sealed trait Instr[-Ctx <: StatelessContext] extends GasSchedule {
  def code: Byte

  def serialize(): ByteString

  // this function needs to charge gas manually
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

sealed trait InstrWithSimpleGas[-Ctx <: StatelessContext] extends GasSimple {
  def runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _ <- frame.ctx.chargeGas(this)
      _ <- _runWith(frame)
    } yield ()
  }

  // this function will not need to take care of charge gas
  def _runWith[C <: Ctx](frame: Frame[C]): ExeResult[Unit]
}

object Instr {
  implicit val statelessSerde: Serde[Instr[StatelessContext]] = new Serde[Instr[StatelessContext]] {
    def serialize(input: Instr[StatelessContext]): ByteString = input.serialize()

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    def _deserialize(input: ByteString): SerdeResult[Staging[Instr[StatelessContext]]] = {
      for {
        code <- input.headOption.toRight(SerdeError.incompleteData(1, 0))
        instrCompanion <- getStatelessCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code")
        )
        output <- instrCompanion.deserialize[StatelessContext](input.tail)
      } yield output
    }
  }
  implicit val statefulSerde: Serde[Instr[StatefulContext]] = new Serde[Instr[StatefulContext]] {
    def serialize(input: Instr[StatefulContext]): ByteString = input.serialize()

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    def _deserialize(input: ByteString): SerdeResult[Staging[Instr[StatefulContext]]] = {
      for {
        code <- input.headOption.toRight(SerdeError.incompleteData(1, 0))
        instrCompanion <- getStatefulCompanion(code).toRight(
          SerdeError.validation(s"Instruction - invalid code $code")
        )
        output <- instrCompanion.deserialize[StatefulContext](input.tail)
      } yield output
    }
  }

  def getStatelessCompanion(byte: Byte): Option[InstrCompanion[StatelessContext]] = {
    statelessInstrs(Bytes.toPosInt(byte))
  }

  def getStatefulCompanion(byte: Byte): Option[InstrCompanion[StatefulContext]] = {
    statefulInstrs(Bytes.toPosInt(byte))
  }

  // format: off
  val statelessInstrs0: AVector[InstrCompanion[StatelessContext]] = AVector(
    ConstTrue, ConstFalse,
    I256Const0, I256Const1, I256Const2, I256Const3, I256Const4, I256Const5, I256ConstN1,
    U256Const0, U256Const1, U256Const2, U256Const3, U256Const4, U256Const5,
    I256Const, U256Const,
    BytesConst, AddressConst,
    LoadLocal, StoreLocal,
    Pop,
    BoolNot, BoolAnd, BoolOr, BoolEq, BoolNeq, BoolToByteVec,
    I256Add, I256Sub, I256Mul, I256Div, I256Mod, I256Eq, I256Neq, I256Lt, I256Le, I256Gt, I256Ge,
    U256Add, U256Sub, U256Mul, U256Div, U256Mod, U256Eq, U256Neq, U256Lt, U256Le, U256Gt, U256Ge,
    U256ModAdd, U256ModSub, U256ModMul, U256BitAnd, U256BitOr, U256Xor, U256SHL, U256SHR,
    I256ToU256, I256ToByteVec, U256ToI256, U256ToByteVec,
    ByteVecEq, ByteVecNeq, ByteVecSize, ByteVecConcat, AddressEq, AddressNeq, AddressToByteVec,
    Jump, IfTrue, IfFalse,
    CallLocal, Return,
    Assert,
    Blake2b, Keccak256, Sha256, Sha3, VerifyTxSignature, VerifySecP256K1, VerifyED25519,
    ChainId, BlockTimeStamp, BlockTarget, TxId, TxCaller, TxCallerSize,
    VerifyAbsoluteLocktime, VerifyRelativeLocktime,
    Log1, Log2, Log3, Log4, Log5
  )
  val statefulInstrs0: AVector[InstrCompanion[StatefulContext]] = AVector(
    LoadField, StoreField, CallExternal,
    ApproveAlf, ApproveToken, AlfRemaining, TokenRemaining, IsPaying,
    TransferAlf, TransferAlfFromSelf, TransferAlfToSelf, TransferToken, TransferTokenFromSelf, TransferTokenToSelf,
    CreateContract, CopyCreateContract, DestroySelf, SelfAddress, SelfContractId, IssueToken,
    CallerAddress, IsCalledFromTxScript, CallerInitialStateHash, ContractInitialStateHash
  )
  // format: on

  val toCode: Map[InstrCompanion[_ <: StatelessContext], Int] = {
    val instrs0: AVector[InstrCompanion[_ <: StatelessContext]] =
      AVector(CallLocal, CallExternal, Return)
    val instrs1: AVector[InstrCompanion[_ <: StatelessContext]] =
      statelessInstrs0.filter(!instrs0.contains(_)).as[InstrCompanion[_]]
    val instrs2: AVector[InstrCompanion[_ <: StatelessContext]] =
      statefulInstrs0.filter(!instrs0.contains(_)).as[InstrCompanion[_]]
    (instrs0.mapWithIndex((instr, index) => (instr, index)) ++
      instrs1.mapWithIndex((instr, index) => (instr, index + 3)) ++
      instrs2.mapWithIndex((instr, index) => (instr, index + 160))).toIterable.toMap
  }

  val statelessInstrs: AVector[Option[InstrCompanion[StatelessContext]]] = {
    val array = Array.fill[Option[InstrCompanion[StatelessContext]]](256)(None)
    statelessInstrs0.foreach(instr => array(toCode(instr)) = Some(instr))
    AVector.unsafe(array)
  }

  val statefulInstrs: AVector[Option[InstrCompanion[StatefulContext]]] = {
    val table = Array.fill[Option[InstrCompanion[StatefulContext]]](256)(None)
    statelessInstrs0.foreach(instr => table(toCode(instr)) = Some(instr))
    statefulInstrs0.foreach(instr => table(toCode(instr)) = Some(instr))
    AVector.unsafe(table)
  }
}

sealed trait StatefulInstr          extends Instr[StatefulContext] with GasSchedule                     {}
sealed trait StatelessInstr         extends StatefulInstr with Instr[StatelessContext] with GasSchedule {}
sealed trait StatefulInstrSimpleGas extends StatefulInstr with InstrWithSimpleGas[StatefulContext]
sealed trait StatelessInstrSimpleGas
    extends StatelessInstr
    with InstrWithSimpleGas[StatelessContext]

sealed trait InstrCompanion[-Ctx <: StatelessContext] {
  lazy val code: Byte = Instr.toCode(this).toByte

  def deserialize[C <: Ctx](input: ByteString): SerdeResult[Staging[Instr[C]]]
}

sealed abstract class InstrCompanion1[Ctx <: StatelessContext, T: Serde]
    extends InstrCompanion[Ctx] {
  def apply(t: T): Instr[Ctx]

  @inline def from[C <: Ctx](t: T): Instr[C] = apply(t)

  def deserialize[C <: Ctx](input: ByteString): SerdeResult[Staging[Instr[C]]] = {
    serdeImpl[T]._deserialize(input).map(_.mapValue(from))
  }
}

sealed abstract class StatelessInstrCompanion1[T: Serde]
    extends InstrCompanion1[StatelessContext, T]

sealed abstract class StatefulInstrCompanion1[T: Serde] extends InstrCompanion1[StatefulContext, T]

sealed trait StatelessInstrCompanion0
    extends InstrCompanion[StatelessContext]
    with Instr[StatelessContext]
    with GasSchedule {
  def serialize(): ByteString = ByteString(code)

  def deserialize[C <: StatelessContext](input: ByteString): SerdeResult[Staging[Instr[C]]] =
    Right(Staging(this, input))
}

sealed trait StatefulInstrCompanion0
    extends InstrCompanion[StatefulContext]
    with Instr[StatefulContext]
    with GasSchedule {
  def serialize(): ByteString = ByteString(code)

  def deserialize[C <: StatefulContext](input: ByteString): SerdeResult[Staging[Instr[C]]] =
    Right(Staging(this, input))
}

sealed trait OperandStackInstr extends StatelessInstrSimpleGas with GasSimple {}

sealed trait ConstInstr extends OperandStackInstr with GasVeryLow
object ConstInstr {
  def i256(v: Val.I256): ConstInstr = {
    val bi = v.v.v
    if (bi.bitLength() <= 8) {
      (bi.intValue(): @switch) match {
        case -1 => I256ConstN1
        case 0  => I256Const0
        case 1  => I256Const1
        case 2  => I256Const2
        case 3  => I256Const3
        case 4  => I256Const4
        case 5  => I256Const5
        case _  => I256Const(v)
      }
    } else {
      I256Const(v)
    }
  }

  def u256(v: Val.U256): ConstInstr = {
    val bi = v.v.v
    if (bi.bitLength() <= 8) {
      (bi.intValue(): @switch) match {
        case 0 => U256Const0
        case 1 => U256Const1
        case 2 => U256Const2
        case 3 => U256Const3
        case 4 => U256Const4
        case 5 => U256Const5
        case _ => U256Const(v)
      }
    } else {
      U256Const(v)
    }
  }
}

sealed trait ConstInstr0 extends ConstInstr with StatelessInstrCompanion0 {
  def const: Val

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(const)
  }
}

sealed abstract class ConstInstr1[T <: Val] extends ConstInstr {
  def const: T

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(const)
  }
}

object ConstTrue  extends ConstInstr0 { val const: Val = Val.Bool(true)  }
object ConstFalse extends ConstInstr0 { val const: Val = Val.Bool(false) }

object I256ConstN1 extends ConstInstr0 { val const: Val = Val.I256(util.I256.NegOne)   }
object I256Const0  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Zero)     }
object I256Const1  extends ConstInstr0 { val const: Val = Val.I256(util.I256.One)      }
object I256Const2  extends ConstInstr0 { val const: Val = Val.I256(util.I256.Two)      }
object I256Const3  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(3L)) }
object I256Const4  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(4L)) }
object I256Const5  extends ConstInstr0 { val const: Val = Val.I256(util.I256.from(5L)) }

object U256Const0 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Zero)       }
object U256Const1 extends ConstInstr0 { val const: Val = Val.U256(util.U256.One)        }
object U256Const2 extends ConstInstr0 { val const: Val = Val.U256(util.U256.Two)        }
object U256Const3 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(3L)) }
object U256Const4 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(4L)) }
object U256Const5 extends ConstInstr0 { val const: Val = Val.U256(util.U256.unsafe(5L)) }

@ByteCode
final case class I256Const(const: Val.I256) extends ConstInstr1[Val.I256] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[util.I256].serialize(const.v)
}
object I256Const extends StatelessInstrCompanion1[Val.I256]
@ByteCode
final case class U256Const(const: Val.U256) extends ConstInstr1[Val.U256] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[util.U256].serialize(const.v)
}
object U256Const extends StatelessInstrCompanion1[Val.U256]

@ByteCode
final case class BytesConst(const: Val.ByteVec) extends ConstInstr1[Val.ByteVec] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[Val.ByteVec].serialize(const)
}
object BytesConst extends StatelessInstrCompanion1[Val.ByteVec]

@ByteCode
final case class AddressConst(const: Val.Address) extends ConstInstr1[Val.Address] {
  def serialize(): ByteString =
    ByteString(code) ++ serdeImpl[Val.Address].serialize(const)
}
object AddressConst extends StatelessInstrCompanion1[Val.Address]

// Note: 0 <= index <= 0xFF
@ByteCode
final case class LoadLocal(index: Byte) extends OperandStackInstr with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getLocalVal(Bytes.toPosInt(index))
      _ <- frame.pushOpStack(v)
    } yield ()
  }
}
object LoadLocal extends StatelessInstrCompanion1[Byte]
@ByteCode
final case class StoreLocal(index: Byte) extends OperandStackInstr with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.popOpStack()
      _ <- frame.setLocalVal(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreLocal extends StatelessInstrCompanion1[Byte]

sealed trait FieldInstr extends StatefulInstrSimpleGas with GasSimple {}
@ByteCode
final case class LoadField(index: Byte) extends FieldInstr with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.getField(Bytes.toPosInt(index))
      _ <- frame.pushOpStack(v)
    } yield ()
  }
}
object LoadField extends StatefulInstrCompanion1[Byte]
@ByteCode
final case class StoreField(index: Byte) extends FieldInstr with GasVeryLow {
  def serialize(): ByteString = ByteString(code, index)
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.popOpStack()
      _ <- frame.setField(Bytes.toPosInt(index), v)
    } yield ()
  }
}
object StoreField extends StatefulInstrCompanion1[Byte]

sealed trait PureStackInstr extends OperandStackInstr with StatelessInstrCompanion0 with GasVeryLow

case object Pop extends PureStackInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.remove(1)
  }
}

sealed trait ArithmeticInstr
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {}

sealed trait BinaryArithmeticInstr[T <: Val] extends ArithmeticInstr with GasSimple {
  protected def op(x: T, y: T): ExeResult[Val]

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value2 <- frame.popOpStackT[T]()
      value1 <- frame.popOpStackT[T]()
      out    <- op(value1, value2)
      _      <- frame.pushOpStack(out)
    } yield ()
  }
}
object BinaryArithmeticInstr {
  def error(a: Val, b: Val, op: ArithmeticInstr): ArithmeticError = {
    ArithmeticError(s"Arithmetic error: $op($a, $b)")
  }

  @inline def i256Op(
      op: (util.I256, util.I256) => util.I256
  )(x: Val.I256, y: Val.I256): ExeResult[Val.I256] =
    Right(Val.I256.apply(op(x.v, y.v)))

  @inline def i256SafeOp(
      instr: ArithmeticInstr,
      op: (util.I256, util.I256) => Option[util.I256]
  )(x: Val.I256, y: Val.I256): ExeResult[Val.I256] =
    op(x.v, y.v).map(Val.I256.apply).toRight(Right(BinaryArithmeticInstr.error(x, y, instr)))

  @inline def u256Op(
      op: (util.U256, util.U256) => util.U256
  )(x: Val.U256, y: Val.U256): ExeResult[Val.U256] =
    Right(Val.U256.apply(op(x.v, y.v)))

  @inline def u256SafeOp(
      instr: ArithmeticInstr,
      op: (util.U256, util.U256) => Option[util.U256]
  )(x: Val.U256, y: Val.U256): ExeResult[Val.U256] =
    op(x.v, y.v).map(Val.U256.apply).toRight(Right(BinaryArithmeticInstr.error(x, y, instr)))

  @inline def i256Comp(
      op: (util.I256, util.I256) => Boolean
  )(x: Val.I256, y: Val.I256): ExeResult[Val.Bool] =
    Right(Val.Bool(op(x.v, y.v)))

  @inline def u256Comp(
      op: (util.U256, util.U256) => Boolean
  )(x: Val.U256, y: Val.U256): ExeResult[Val.Bool] =
    Right(Val.Bool(op(x.v, y.v)))
}
object I256Add extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] = {
    BinaryArithmeticInstr.i256SafeOp(this, _.add(_))(x, y)
  }
}
object I256Sub extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.sub(_))(x, y)
}
object I256Mul extends BinaryArithmeticInstr[Val.I256] with GasLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mul(_))(x, y)
}
object I256Div extends BinaryArithmeticInstr[Val.I256] with GasLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.div(_))(x, y)
}
object I256Mod extends BinaryArithmeticInstr[Val.I256] with GasLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256SafeOp(this, _.mod(_))(x, y)
}
object I256Eq extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.==(_))(x, y)
}
object I256Neq extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.!=(_))(x, y)
}
object I256Lt extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.<(_))(x, y)
}
object I256Le extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.<=(_))(x, y)
}
object I256Gt extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.>(_))(x, y)
}
object I256Ge extends BinaryArithmeticInstr[Val.I256] with GasVeryLow {
  protected def op(x: Val.I256, y: Val.I256): ExeResult[Val] =
    BinaryArithmeticInstr.i256Comp(_.>=(_))(x, y)
}
object U256Add extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.add(_))(x, y)
}
object U256Sub extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.sub(_))(x, y)
}
object U256Mul extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mul(_))(x, y)
}
object U256Div extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.div(_))(x, y)
}
object U256Mod extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256SafeOp(this, _.mod(_))(x, y)
}
object U256ModAdd extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.modAdd(_))(x, y)
}
object U256ModSub extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.modSub(_))(x, y)
}
object U256ModMul extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.modMul(_))(x, y)
}
object U256BitAnd extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.bitAnd(_))(x, y)
}
object U256BitOr extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.bitOr(_))(x, y)
}
object U256Xor extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op(_.xor(_))(x, y)
}
object U256SHL extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op((x, y) => x.shl(y))(x, y)
}
object U256SHR extends BinaryArithmeticInstr[Val.U256] with GasLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Op((x, y) => x.shr(y))(x, y)
}
object U256Eq extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.==(_))(x, y)
}
object U256Neq extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.!=(_))(x, y)
}
object U256Lt extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.<(_))(x, y)
}
object U256Le extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.<=(_))(x, y)
}
object U256Gt extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.>(_))(x, y)
}
object U256Ge extends BinaryArithmeticInstr[Val.U256] with GasVeryLow {
  protected def op(x: Val.U256, y: Val.U256): ExeResult[Val] =
    BinaryArithmeticInstr.u256Comp(_.>=(_))(x, y)
}

sealed trait LogicInstr
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {}
case object BoolNot extends LogicInstr with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool <- frame.popOpStackT[Val.Bool]()
      _    <- frame.pushOpStack(bool.not)
    } yield ()
  }
}
sealed trait BinaryBool extends LogicInstr with GasVeryLow {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      bool2 <- frame.popOpStackT[Val.Bool]()
      bool1 <- frame.popOpStackT[Val.Bool]()
      _     <- frame.pushOpStack(op(bool1, bool2))
    } yield ()
  }
}
case object BoolAnd extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = bool1.and(bool2)
}
case object BoolOr extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = bool1.or(bool2)
}
case object BoolEq extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = Val.Bool(bool1 == bool2)
}
case object BoolNeq extends BinaryBool {
  def op(bool1: Val.Bool, bool2: Val.Bool): Val.Bool = Val.Bool(bool1 != bool2)
}

sealed trait ToByteVecInstr[R <: Val, U <: Val]
    extends StatelessInstr
    with GasToByte
    with StatelessInstrCompanion0 {
  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      from <- frame.popOpStackT[R]()
      byteVec = from.toByteVec()
      _ <- frame.pushOpStack(byteVec)
      _ <- frame.ctx.chargeGasWithSize(this, byteVec.bytes.size)
    } yield ()
  }
}
case object BoolToByteVec extends ToByteVecInstr[Val.Bool, Val.ByteVec]

sealed trait ConversionInstr[R <: Val, U <: Val]
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasSimple {

  def converse(from: R): ExeResult[U]

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      from <- frame.popOpStackT[R]()
      to   <- converse(from)
      _    <- frame.pushOpStack(to)
    } yield ()
  }
}

case object I256ToU256 extends ConversionInstr[Val.I256, Val.U256] with GasVeryLow {
  def converse(from: Val.I256): ExeResult[Val.U256] = {
    util.U256.fromI256(from.v).map(Val.U256.apply).toRight(Right(InvalidConversion(from, Val.U256)))
  }
}
case object I256ToByteVec extends ToByteVecInstr[Val.I256, Val.ByteVec]
case object U256ToI256 extends ConversionInstr[Val.U256, Val.I256] with GasVeryLow {
  def converse(from: Val.U256): ExeResult[Val.I256] = {
    util.I256.fromU256(from.v).map(Val.I256.apply).toRight(Right(InvalidConversion(from, Val.I256)))
  }
}
case object U256ToByteVec extends ToByteVecInstr[Val.U256, Val.ByteVec]

sealed trait ComparisonInstr[T <: Val]
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasVeryLow {
  def op(x: T, y: T): Val.Bool

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      x <- frame.popOpStackT[T]()
      y <- frame.popOpStackT[T]()
      _ <- frame.pushOpStack(op(x, y))
    } yield ()
  }
}
sealed trait EqT[T <: Val] extends ComparisonInstr[T] {
  def op(x: T, y: T): Val.Bool = Val.Bool(x == y)
}
sealed trait NeT[T <: Val] extends ComparisonInstr[T] {
  def op(x: T, y: T): Val.Bool = Val.Bool(x != y)
}
case object ByteVecEq  extends EqT[Val.ByteVec]
case object ByteVecNeq extends NeT[Val.ByteVec]
case object ByteVecSize
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v <- frame.popOpStackT[Val.ByteVec]()
      _ <- frame.pushOpStack(Val.U256(util.U256.unsafe(v.bytes.size)))
    } yield ()
  }
}
case object ByteVecConcat
    extends StatelessInstrSimpleGas
    with StatelessInstrCompanion0
    with GasLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      v2 <- frame.popOpStackT[Val.ByteVec]()
      v1 <- frame.popOpStackT[Val.ByteVec]()
      _  <- frame.pushOpStack(Val.ByteVec(v1.bytes ++ v2.bytes))
    } yield ()
  }
}
case object AddressEq        extends EqT[Val.Address]
case object AddressNeq       extends NeT[Val.Address]
case object AddressToByteVec extends ToByteVecInstr[Val.Address, Val.ByteVec]

sealed trait ObjectInstr   extends StatelessInstr with GasSchedule {}
sealed trait NewBooleanVec extends ObjectInstr with GasSchedule    {}
sealed trait NewByteVec    extends ObjectInstr with GasSchedule    {}
sealed trait NewI256Vec    extends ObjectInstr with GasSchedule    {}
sealed trait NewU256Vec    extends ObjectInstr with GasSchedule    {}
sealed trait NewByte256Vec extends ObjectInstr with GasSchedule    {}

sealed trait ControlInstr extends StatelessInstrSimpleGas with GasHigh {
  def code: Byte
  def offset: Int

  def serialize(): ByteString = ByteString(code) ++ serdeImpl[Int].serialize(offset)
}

sealed trait ControlCompanion[T <: StatelessInstr] extends InstrCompanion[StatelessContext] {
  def apply(offset: Int): T

  def deserialize[C <: StatelessContext](input: ByteString): SerdeResult[Staging[T]] = {
    ControlCompanion.offsetSerde._deserialize(input).map(_.mapValue(apply))
  }
}
object ControlCompanion {
  def validate(n: Int): Boolean = (n <= (1 << 16)) && (n >= -(1 << 16))

  val offsetSerde: Serde[Int] = serdeImpl[Int].validate(offset =>
    if (validate(offset)) Right(()) else Left(s"Invalid offset $offset")
  )
}

@ByteCode
final case class Jump(offset: Int) extends ControlInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.offsetPC(offset)
  }
}
object Jump extends ControlCompanion[Jump]

sealed trait IfJumpInstr extends ControlInstr {
  def condition(value: Val.Bool): Boolean

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.popOpStackT[Val.Bool]()
      _     <- if (condition(value)) frame.offsetPC(offset) else okay
    } yield ()
  }
}
@ByteCode
final case class IfTrue(offset: Int) extends IfJumpInstr {
  def condition(value: Val.Bool): Boolean = value.v
}
object IfTrue extends ControlCompanion[IfTrue]

@ByteCode
final case class IfFalse(offset: Int) extends IfJumpInstr {
  def condition(value: Val.Bool): Boolean = !value.v
}
object IfFalse extends ControlCompanion[IfFalse]

sealed trait CallInstr
@ByteCode
final case class CallLocal(index: Byte) extends CallInstr with StatelessInstr with GasCall {
  def serialize(): ByteString = ByteString(code, index)

  // Implemented in frame instead
  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = ???
}
object CallLocal extends StatelessInstrCompanion1[Byte]
@ByteCode
final case class CallExternal(index: Byte) extends CallInstr with StatefulInstr with GasCall {
  def serialize(): ByteString = ByteString(code, index)

  // Implemented in frame instead
  def runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = ???
}
object CallExternal extends StatefulInstrCompanion1[Byte]

case object Return extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasZero {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      value <- frame.opStack.pop(frame.method.returnLength)
      _     <- frame.returnTo(value)
    } yield frame.complete()
  }
}

case object Assert extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasVeryLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      predicate <- frame.popOpStackT[Val.Bool]()
      _         <- if (predicate.v) okay else failed(AssertionFailed)
    } yield ()
  }
}

sealed trait CryptoInstr extends StatelessInstr with GasSchedule

sealed abstract class HashAlg[H <: RandomBytes]
    extends CryptoInstr
    with StatelessInstrCompanion0
    with GasHash {
  def hash(bs: ByteString): H

  def runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      input <- frame.popOpStackT[Val.ByteVec]()
      _     <- frame.ctx.chargeGasWithSize(this, input.bytes.length)
      _     <- frame.pushOpStack(Val.ByteVec.from(hash(input.bytes)))
    } yield ()
  }
}

object HashAlg {
  trait Blake2bHash {
    def hash(bs: ByteString): crypto.Blake2b = crypto.Blake2b.hash(bs)
  }

  trait Keccak256Hash {
    def hash(bs: ByteString): crypto.Keccak256 = crypto.Keccak256.hash(bs)
  }

  trait Sha256Hash {
    def hash(bs: ByteString): crypto.Sha256 = crypto.Sha256.hash(bs)
  }

  trait Sha3Hash {
    def hash(bs: ByteString): crypto.Sha3 = crypto.Sha3.hash(bs)
  }
}

case object Blake2b   extends HashAlg[crypto.Blake2b] with HashAlg.Blake2bHash
case object Keccak256 extends HashAlg[crypto.Keccak256] with HashAlg.Keccak256Hash
case object Sha256    extends HashAlg[crypto.Sha256] with HashAlg.Sha256Hash
case object Sha3      extends HashAlg[crypto.Sha3] with HashAlg.Sha3Hash

sealed trait SignatureInstr extends CryptoInstr with StatelessInstrSimpleGas with GasSimple

case object VerifyTxSignature
    extends SignatureInstr
    with StatelessInstrCompanion0
    with GasSignature {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    val rawData    = frame.ctx.txId.bytes
    val signatures = frame.ctx.signatures
    for {
      rawPublicKey <- frame.popOpStackT[Val.ByteVec]()
      publicKey    <- PublicKey.from(rawPublicKey.bytes).toRight(Right(InvalidPublicKey))
      signature    <- signatures.pop()
      _ <- {
        if (SignatureSchema.verify(rawData, signature, publicKey)) {
          okay
        } else {
          failed(InvalidSignature)
        }
      }
    } yield ()
  }
}

sealed trait GenericVerifySignature[PubKey, Sig]
    extends SignatureInstr
    with StatelessInstrCompanion0
    with GasSignature {
  def buildPubKey(value: Val.ByteVec): Option[PubKey]
  def buildSignature(value: Val.ByteVec): Option[Sig]
  def verify(data: ByteString, signature: Sig, pubKey: PubKey): Boolean

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      rawSignature <- frame.popOpStackT[Val.ByteVec]()
      signature    <- buildSignature(rawSignature).toRight(Right(InvalidSignatureFormat))
      rawPublicKey <- frame.popOpStackT[Val.ByteVec]()
      publicKey    <- buildPubKey(rawPublicKey).toRight(Right(InvalidPublicKey))
      rawData      <- frame.popOpStackT[Val.ByteVec]()
      _            <- if (rawData.bytes.length == 32) okay else failed(SignedDataIsNot32Bytes)
      _            <- if (verify(rawData.bytes, signature, publicKey)) okay else failed(InvalidSignature)
    } yield ()
  }
}

case object VerifySecP256K1
    extends GenericVerifySignature[crypto.SecP256K1PublicKey, crypto.SecP256K1Signature] {
  def buildPubKey(value: Val.ByteVec): Option[crypto.SecP256K1PublicKey] =
    crypto.SecP256K1PublicKey.from(value.bytes)
  def buildSignature(value: Val.ByteVec): Option[crypto.SecP256K1Signature] =
    crypto.SecP256K1Signature.from(value.bytes)
  def verify(
      data: ByteString,
      signature: crypto.SecP256K1Signature,
      pubKey: crypto.SecP256K1PublicKey
  ): Boolean =
    crypto.SecP256K1.verify(data, signature, pubKey)
}

case object VerifyED25519
    extends GenericVerifySignature[crypto.ED25519PublicKey, crypto.ED25519Signature] {
  def buildPubKey(value: Val.ByteVec): Option[crypto.ED25519PublicKey] =
    crypto.ED25519PublicKey.from(value.bytes)
  def buildSignature(value: Val.ByteVec): Option[crypto.ED25519Signature] =
    crypto.ED25519Signature.from(value.bytes)
  def verify(
      data: ByteString,
      signature: crypto.ED25519Signature,
      pubKey: crypto.ED25519PublicKey
  ): Boolean =
    crypto.ED25519.verify(data, signature, pubKey)
}

sealed trait AssetInstr extends StatefulInstrSimpleGas with GasBalance

object ApproveAlf extends AssetInstr with StatefulInstrCompanion0 {
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  )
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      amount       <- frame.popOpStackT[Val.U256]()
      address      <- frame.popOpStackT[Val.Address]()
      balanceState <- frame.getBalanceState()
      _ <- balanceState
        .approveALF(address.lockupScript, amount.v)
        .toRight(Right(NotEnoughBalance))
    } yield ()
  }
}

object ApproveToken extends AssetInstr with StatefulInstrCompanion0 {
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.JavaSerializable",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable"
    )
  )
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      amount       <- frame.popOpStackT[Val.U256]()
      tokenIdRaw   <- frame.popOpStackT[Val.ByteVec]()
      tokenId      <- Hash.from(tokenIdRaw.bytes).toRight(Right(InvalidTokenId))
      address      <- frame.popOpStackT[Val.Address]()
      balanceState <- frame.getBalanceState()
      _ <- balanceState
        .approveToken(address.lockupScript, tokenId, amount.v)
        .toRight(Right(NotEnoughBalance))
    } yield ()
  }
}

object AlfRemaining extends AssetInstr with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address      <- frame.popOpStackT[Val.Address]()
      balanceState <- frame.getBalanceState()
      amount <- balanceState
        .alfRemaining(address.lockupScript)
        .toRight(Right(NoAlfBalanceForTheAddress))
      _ <- frame.pushOpStack(Val.U256(amount))
    } yield ()
  }
}

object TokenRemaining extends AssetInstr with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      tokenIdRaw   <- frame.popOpStackT[Val.ByteVec]()
      address      <- frame.popOpStackT[Val.Address]()
      tokenId      <- Hash.from(tokenIdRaw.bytes).toRight(Right(InvalidTokenId))
      balanceState <- frame.getBalanceState()
      amount <- balanceState
        .tokenRemaining(address.lockupScript, tokenId)
        .toRight(Right(NoTokenBalanceForTheAddress))
      _ <- frame.pushOpStack(Val.U256(amount))
    } yield ()
  }
}

object IsPaying extends AssetInstr with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address      <- frame.popOpStackT[Val.Address]()
      balanceState <- frame.getBalanceState()
      isPaying = balanceState.isPaying(address.lockupScript)
      _ <- frame.pushOpStack(Val.Bool(isPaying))
    } yield ()
  }
}

sealed trait Transfer extends AssetInstr {
  def getContractLockupScript[C <: StatefulContext](frame: Frame[C]): ExeResult[LockupScript] = {
    frame.obj.getContractId().map(LockupScript.p2c)
  }

  def transferAlf[C <: StatefulContext](
      frame: Frame[C],
      fromThunk: => ExeResult[LockupScript],
      toThunk: => ExeResult[LockupScript]
  ): ExeResult[Unit] = {
    for {
      amount       <- frame.popOpStackT[Val.U256]()
      to           <- toThunk
      from         <- fromThunk
      balanceState <- frame.getBalanceState()
      _            <- balanceState.useAlf(from, amount.v).toRight(Right(NotEnoughBalance))
      _ <- frame.ctx.outputBalances
        .addAlf(to, amount.v)
        .toRight(Right(BalanceOverflow))
    } yield ()
  }

  def transferToken[C <: StatefulContext](
      frame: Frame[C],
      fromThunk: => ExeResult[LockupScript],
      toThunk: => ExeResult[LockupScript]
  ): ExeResult[Unit] = {
    for {
      amount       <- frame.popOpStackT[Val.U256]()
      tokenIdRaw   <- frame.popOpStackT[Val.ByteVec]()
      tokenId      <- Hash.from(tokenIdRaw.bytes).toRight(Right(InvalidTokenId))
      to           <- toThunk
      from         <- fromThunk
      balanceState <- frame.getBalanceState()
      _ <- balanceState
        .useToken(from, tokenId, amount.v)
        .toRight(Right(NotEnoughBalance))
      _ <- frame.ctx.outputBalances
        .addToken(to, tokenId, amount.v)
        .toRight(Right(BalanceOverflow))
    } yield ()
  }
}

object TransferAlf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlf(
      frame,
      frame.popOpStackT[Val.Address]().map(_.lockupScript),
      frame.popOpStackT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferAlfFromSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlf(
      frame,
      getContractLockupScript(frame),
      frame.popOpStackT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferAlfToSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferAlf(
      frame,
      frame.popOpStackT[Val.Address]().map(_.lockupScript),
      getContractLockupScript(frame)
    )
  }
}

object TransferToken extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      frame.popOpStackT[Val.Address]().map(_.lockupScript),
      frame.popOpStackT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferTokenFromSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      getContractLockupScript(frame),
      frame.popOpStackT[Val.Address]().map(_.lockupScript)
    )
  }
}

object TransferTokenToSelf extends Transfer with StatefulInstrCompanion0 {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    transferToken(
      frame,
      frame.popOpStackT[Val.Address]().map(_.lockupScript),
      getContractLockupScript(frame)
    )
  }
}

sealed trait ContractInstr
    extends StatefulInstrSimpleGas
    with StatefulInstrCompanion0
    with GasSimple {}

object CreateContract extends ContractInstr with GasCreate {
  val fieldsSerde: Serde[AVector[Val]] = serdeImpl[AVector[Val]]

  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      fields          <- frame.popFields()
      contractCodeRaw <- frame.popOpStackT[Val.ByteVec]()
      contractCode <- decode[StatefulContract](contractCodeRaw.bytes).left.map(e =>
        Right(SerdeErrorCreateContract(e))
      )
      _ <- StatefulContract.check(contractCode)
      _ <- {
        val initialStateHash =
          Hash.doubleHash(contractCodeRaw.bytes ++ fieldsSerde.serialize(fields))
        frame.createContract(contractCode.toHalfDecoded(), initialStateHash, fields)
      }
    } yield ()
  }
}

object CopyCreateContract extends ContractInstr with GasCreate {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      fields      <- frame.popFields()
      contractId  <- frame.popContractId()
      contractObj <- frame.ctx.loadContractObj(contractId)
      _ <- {
        val contractCodeBytes = encode(contractObj.code)
        val initialStateHash =
          Hash.doubleHash(contractCodeBytes ++ CreateContract.fieldsSerde.serialize(fields))
        frame.createContract(contractObj.code, initialStateHash, fields)
      }
    } yield ()
  }
}

// This instruction can only be called from Tx, i.e. IsCalledFromTxScript should return true
// This instruction will result in an immediate run of Return
object DestroySelf extends ContractInstr with GasDestroy {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerFrame <- frame.getCallerFrame()
      _           <- if (callerFrame.obj.isScript()) okay else failed(ContractDestructionShouldBeCalledFromTx)
      address     <- frame.popOpStackT[Val.Address]()
      _           <- frame.destroyContract(address.lockupScript)
    } yield ()
  }
}

object SelfAddress extends ContractInstr with GasVeryLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      address <- frame.obj.getAddress()
      _       <- frame.pushOpStack(address)
    } yield ()
  }
}

object SelfContractId extends ContractInstr with GasVeryLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      contractId <- frame.obj.getContractId()
      _          <- frame.pushOpStack(Val.ByteVec(contractId.bytes))
    } yield ()
  }
}

object IssueToken extends ContractInstr with GasBalance {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      _          <- Either.cond(frame.method.isPayable, (), Right(ExpectNonPayableMethod))
      contractId <- frame.obj.getContractId()
      amount     <- frame.popOpStackT[Val.U256]()
      tokenId = contractId // tokenId is addressHash
      _ <- frame.ctx.outputBalances
        .addToken(LockupScript.p2c(contractId), tokenId, amount.v)
        .toRight(Right(BalanceOverflow))
    } yield ()
  }
}

// only return the address when the caller is a contract
object CallerAddress extends ContractInstr with GasLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerFrame <- frame.getCallerFrame()
      address     <- callerFrame.obj.getAddress()
      _           <- frame.pushOpStack(address)
    } yield ()
  }
}

object IsCalledFromTxScript extends ContractInstr with GasLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerFrame <- frame.getCallerFrame()
      _           <- frame.pushOpStack(Val.Bool(callerFrame.obj.isScript()))
    } yield ()
  }
}

object CallerInitialStateHash extends ContractInstr with GasLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerFrame <- frame.getCallerFrame()
      statefulObj <- callerFrame.obj match {
        case obj: StatefulContractObject => Right(obj)
        case _                           => failed(ExpectStatefulContractObj)
      }
      _ <- frame.pushOpStack(statefulObj.getInitialStateHash())
    } yield ()
  }
}

object ContractInitialStateHash extends ContractInstr with GasLow {
  def _runWith[C <: StatefulContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      contractId  <- frame.popContractId()
      contractObj <- frame.ctx.loadContractObj(contractId)
      _           <- frame.pushOpStack(contractObj.getInitialStateHash())
    } yield ()
  }
}

sealed trait BlockInstr extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasLow

object ChainId extends BlockInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack {
      val id = frame.ctx.blockEnv.chainId.id
      Val.ByteVec(ByteString(id))
    }
  }
}

object BlockTimeStamp extends BlockInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      timestamp <- {
        val millis = frame.ctx.blockEnv.timeStamp.millis
        util.U256.fromLong(millis).toRight(Right(NegativeTimeStamp(millis)))
      }
      _ <- frame.pushOpStack(Val.U256(timestamp))
    } yield ()
  }
}

object BlockTarget extends BlockInstr {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      target <- {
        val value = frame.ctx.blockEnv.target.value
        util.U256.from(value).toRight(Right(InvalidTarget(value)))
      }
      _ <- frame.pushOpStack(Val.U256(target))
    } yield ()
  }
}

sealed trait TxInstr extends StatelessInstrSimpleGas with StatelessInstrCompanion0

object TxId extends TxInstr with GasLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.ByteVec(frame.ctx.txId.bytes))
  }
}

object TxCaller extends TxInstr with GasLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      callerIndex <- frame.popOpStackT[Val.U256]()
      caller      <- frame.ctx.getTxCaller(callerIndex)
      _           <- frame.pushOpStack(caller)
    } yield ()
  }
}

object TxCallerSize extends TxInstr with GasLow {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.pushOpStack(Val.U256(util.U256.unsafe(frame.ctx.txEnv.prevOutputs.length)))
  }
}

sealed trait LockTimeInstr extends TxInstr {
  def popTimeStamp[C <: StatelessContext](frame: Frame[C]): ExeResult[TimeStamp] = {
    for {
      u256 <- frame.popOpStackT[Val.U256]()
      res  <- u256.v.toLong.map(TimeStamp.unsafe).toRight(Right(LockTimeOverflow))
    } yield res
  }

  def popDuraton[C <: StatelessContext](frame: Frame[C]): ExeResult[Duration] = {
    for {
      u256 <- frame.popOpStackT[Val.U256]()
      res  <- u256.v.toLong.map(Duration.unsafe).toRight(Right(LockTimeOverflow))
    } yield res
  }
}

object VerifyAbsoluteLocktime extends LockTimeInstr with GasMid {
  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      lockUntil <- popTimeStamp(frame)
      _ <-
        if (lockUntil > frame.ctx.blockEnv.timeStamp) {
          failed(AbsoluteLockTimeVerificationFailed)
        } else {
          okay
        }
    } yield ()
  }
}

object VerifyRelativeLocktime extends LockTimeInstr with GasHigh {
  def getLockUntil(output: AssetOutput, lockDuration: Duration): ExeResult[TimeStamp] = {
    val lockTime = output.lockTime
    if (lockTime.isZero()) {
      // when the lock time of the Utxo is zero, it's not persisted into worldstate yet
      failed(RelativeLockTimeExpectPersistedUtxo)
    } else {
      lockTime.plus(lockDuration).toRight(Right(LockTimeOverflow))
    }
  }

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    for {
      lockDuration    <- popDuraton(frame)
      prevOutputIndex <- frame.popOpStackT[Val.U256]()
      preOutput       <- frame.ctx.getTxPrevOutput(prevOutputIndex)
      lockUntil       <- getLockUntil(preOutput, lockDuration)
      _ <-
        if (lockUntil > frame.ctx.blockEnv.timeStamp) {
          failed(RelativeLockTimeVerificationFailed)
        } else {
          okay
        }
    } yield {}
  }
}

sealed trait Log extends StatelessInstrSimpleGas with StatelessInstrCompanion0 with GasHigh {
  def n: Int

  def _runWith[C <: StatelessContext](frame: Frame[C]): ExeResult[Unit] = {
    frame.opStack.pop(n).map(_ => ()) // TODO: send the log to an event bus
  }
}
object Log1 extends Log { val n: Int = 1 }
object Log2 extends Log { val n: Int = 2 }
object Log3 extends Log { val n: Int = 3 }
object Log4 extends Log { val n: Int = 4 }
object Log5 extends Log { val n: Int = 5 }
