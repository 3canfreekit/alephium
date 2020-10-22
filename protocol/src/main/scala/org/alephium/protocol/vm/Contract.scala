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

import scala.collection.mutable

import org.alephium.protocol.{Hash, HashSerde}
import org.alephium.serde._
import org.alephium.util.AVector

final case class Method[Ctx <: Context](
    isPublic: Boolean,
    isPayable: Boolean,
    argsType: AVector[Val.Type],
    localsLength: Int,
    returnType: AVector[Val.Type],
    instrs: AVector[Instr[Ctx]]
) {
  def check(args: AVector[Val]): ExeResult[Unit] = {
    if (args.length != argsType.length)
      Left(InvalidMethodArgLength(args.length, argsType.length))
    else if (!args.forallWithIndex((v, index) => v.tpe == argsType(index))) {
      Left(InvalidMethodParamsType)
    } else Right(())
  }
}

object Method {
  implicit val statelessSerde: Serde[Method[StatelessContext]] =
    Serde.forProduct6(
      Method[StatelessContext],
      t => (t.isPublic, t.isPayable, t.argsType, t.localsLength, t.returnType, t.instrs))
  implicit val statefulSerde: Serde[Method[StatefulContext]] =
    Serde.forProduct6(
      Method[StatefulContext],
      t => (t.isPublic, t.isPayable, t.argsType, t.localsLength, t.returnType, t.instrs))

  def forMPT: Method[StatefulContext] =
    Method[StatefulContext](isPublic  = false,
                            isPayable = false,
                            AVector.empty,
                            0,
                            AVector.empty,
                            AVector(Pop))
}

sealed trait Contract[Ctx <: Context] {
  def fields: AVector[Val.Type]
  def methods: AVector[Method[Ctx]]
}

object Contract {
  val emptyFields: AVector[Val.Type] = AVector.ofSize(0)
}

sealed abstract class Script[Ctx <: Context] extends Contract[Ctx] {
  val fields: AVector[Val.Type] = Contract.emptyFields

  def toObject: ScriptObj[Ctx]
}

final case class StatelessScript(methods: AVector[Method[StatelessContext]])
    extends Script[StatelessContext] {
  override def toObject: ScriptObj[StatelessContext] = {
    new StatelessScriptObject(this)
  }
}

object StatelessScript {
  implicit val serde: Serde[StatelessScript] =
    Serde.forProduct1(StatelessScript.apply, _.methods)
}

final case class StatefulScript(methods: AVector[Method[StatefulContext]])
    extends Script[StatefulContext] {
  def entryMethod: Method[StatefulContext] = methods.head

  override def toObject: ScriptObj[StatefulContext] = {
    StatefulScriptObject(this)
  }
}

object StatefulScript {
  implicit val serde: Serde[StatefulScript] = Serde.forProduct1(StatefulScript.apply, _.methods)
}

final case class StatefulContract(
    fields: AVector[Val.Type],
    methods: AVector[Method[StatefulContext]]
) extends HashSerde[StatefulContract]
    with Contract[StatefulContext] {
  override lazy val hash: Hash = _getHash

  def toObject(address: Hash, contractState: ContractState): StatefulContractObject = {
    StatefulContractObject(this, contractState.fields.toArray, address)
  }

  def toObject(address: Hash, fields: AVector[Val]): StatefulContractObject = {
    StatefulContractObject(this, fields.toArray, address)
  }
}

object StatefulContract {
  implicit val serde: Serde[StatefulContract] =
    Serde.forProduct2(StatefulContract.apply, t => (t.fields, t.methods))

  val forMPT: StatefulContract = StatefulContract(AVector.empty, AVector(Method.forMPT))
}

sealed trait ContractObj[Ctx <: Context] {
  def addressOpt: Option[Hash]
  def code: Contract[Ctx]
  def fields: mutable.ArraySeq[Val]

  def reloadFields(ctx: Ctx): ExeResult[Unit]
  def commitFields(ctx: Ctx): ExeResult[Unit]

  def getMethod(index: Int): Option[Method[Ctx]] = {
    code.methods.get(index)
  }

  def buildNonPayableFrame(ctx: Ctx,
                           obj: ContractObj[Ctx],
                           method: Method[Ctx],
                           args: AVector[Val],
                           operandStack: Stack[Val],
                           returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx]

  def buildPayableFrame(ctx: Ctx,
                        balanceState: Frame.BalanceState,
                        obj: ContractObj[Ctx],
                        method: Method[Ctx],
                        args: AVector[Val],
                        operandStack: Stack[Val],
                        returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx]

  def startFrame(ctx: Ctx,
                 methodIndex: Int,
                 args: AVector[Val],
                 operandStack: Stack[Val]): ExeResult[Frame[Ctx]] = {
    for {
      method <- getMethod(methodIndex).toRight[ExeFailure](InvalidMethodIndex(methodIndex))
      _      <- if (method.isPublic) Right(()) else Left(PrivateExternalMethodCall)
      frame <- {
        val returnTo: AVector[Val] => ExeResult[Unit] = returns =>
          if (returns.nonEmpty) Left(NonEmptyReturnForMainFunction) else Right(())
        if (method.isPayable) {
          startPayableFrame(ctx, method, args, operandStack, returnTo)
        } else {
          startNonPayableFrame(ctx, method, args, operandStack, returnTo)
        }
      }
    } yield frame
  }

  protected def startPayableFrame(
      ctx: Ctx,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]): ExeResult[Frame[Ctx]] = {
    ctx.getInitialBalances.map(
      balances =>
        buildPayableFrame(ctx,
                          Frame.BalanceState.from(balances),
                          this,
                          method,
                          args,
                          operandStack,
                          returnTo))
  }

  protected def startNonPayableFrame(
      ctx: Ctx,
      method: Method[Ctx],
      args: AVector[Val],
      operandStack: Stack[Val],
      returnTo: AVector[Val] => ExeResult[Unit]): ExeResult[Frame[Ctx]] = {
    Right(buildNonPayableFrame(ctx, this, method, args, operandStack, returnTo))
  }

  def startFrameWithOutputs(ctx: Ctx,
                            methodIndex: Int,
                            args: AVector[Val],
                            operandStack: Stack[Val],
                            returnTo: AVector[Val] => ExeResult[Unit]): ExeResult[Frame[Ctx]] = {
    for {
      method <- getMethod(methodIndex).toRight[ExeFailure](InvalidMethodIndex(methodIndex))
      _      <- if (method.isPublic) Right(()) else Left(PrivateExternalMethodCall)
      frame <- if (method.isPayable) {
        startPayableFrame(ctx, method, args, operandStack, returnTo)
      } else {
        startNonPayableFrame(ctx, method, args, operandStack, returnTo)
      }
    } yield frame
  }
}

sealed trait ScriptObj[Ctx <: Context] extends ContractObj[Ctx] {
  val addressOpt: Option[Hash]      = None
  val fields: mutable.ArraySeq[Val] = mutable.ArraySeq.empty
}

final case class StatelessScriptObject(code: StatelessScript) extends ScriptObj[StatelessContext] {
  def commitFields(ctx: StatelessContext): ExeResult[Unit] = Right(())
  def reloadFields(ctx: StatelessContext): ExeResult[Unit] = Right(())

  def buildNonPayableFrame(ctx: StatelessContext,
                           obj: ContractObj[StatelessContext],
                           method: Method[StatelessContext],
                           args: AVector[Val],
                           operandStack: Stack[Val],
                           returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatelessContext] =
    Frame.stateless(ctx, obj, method, args, operandStack, returnTo)

  def buildPayableFrame(ctx: StatelessContext,
                        balanceState: Frame.BalanceState,
                        obj: ContractObj[StatelessContext],
                        method: Method[StatelessContext],
                        args: AVector[Val],
                        operandStack: Stack[Val],
                        returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatelessContext] =
    ??? // should not be called
}

final case class StatefulScriptObject(code: StatefulScript) extends ScriptObj[StatefulContext] {
  def commitFields(ctx: StatefulContext): ExeResult[Unit] = Right(())
  def reloadFields(ctx: StatefulContext): ExeResult[Unit] = Right(())

  def buildNonPayableFrame(ctx: StatefulContext,
                           obj: ContractObj[StatefulContext],
                           method: Method[StatefulContext],
                           args: AVector[Val],
                           operandStack: Stack[Val],
                           returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatefulContext] =
    Frame.stateful(ctx, None, obj, method, args, operandStack, returnTo)

  def buildPayableFrame(ctx: StatefulContext,
                        balanceState: Frame.BalanceState,
                        obj: ContractObj[StatefulContext],
                        method: Method[StatefulContext],
                        args: AVector[Val],
                        operandStack: Stack[Val],
                        returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatefulContext] =
    Frame.stateful(ctx, Some(balanceState), obj, method, args, operandStack, returnTo)
}

final case class StatefulContractObject(code: StatefulContract,
                                        fields: mutable.ArraySeq[Val],
                                        address: Hash)
    extends ContractObj[StatefulContext] {
  override def addressOpt: Option[Hash] = Some(address)

  def commitFields(ctx: StatefulContext): ExeResult[Unit] = {
    ctx.updateState(address, AVector.from(fields))
  }

  def reloadFields(ctx: StatefulContext): ExeResult[Unit] = {
    ctx.worldState.getContractState(address) match {
      case Right(state) =>
        assume(state.fields.length == fields.length)
        fields.indices.foreach { i =>
          fields(i) = state.fields(i)
        }
        Right(())
      case Left(error) => Left(IOErrorLoadContract(error))
    }
  }

  def buildNonPayableFrame(ctx: StatefulContext,
                           obj: ContractObj[StatefulContext],
                           method: Method[StatefulContext],
                           args: AVector[Val],
                           operandStack: Stack[Val],
                           returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatefulContext] =
    Frame.stateful(ctx, None, obj, method, args, operandStack, returnTo)

  def buildPayableFrame(ctx: StatefulContext,
                        balanceState: Frame.BalanceState,
                        obj: ContractObj[StatefulContext],
                        method: Method[StatefulContext],
                        args: AVector[Val],
                        operandStack: Stack[Val],
                        returnTo: AVector[Val] => ExeResult[Unit]): Frame[StatefulContext] =
    Frame.stateful(ctx, Some(balanceState), obj, method, args, operandStack, returnTo)
}
