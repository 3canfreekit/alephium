package org.alephium.protocol.vm

import scala.annotation.tailrec

import org.alephium.protocol.ALF
import org.alephium.util.AVector

class Frame[Ctx <: Context](var pc: Int,
                            obj: ContractObj[Ctx],
                            val opStack: Stack[Val],
                            val method: Method[Ctx],
                            locals: Array[Val],
                            val returnTo: AVector[Val] => ExeResult[Unit],
                            val ctx: Ctx) {
  def currentInstr: Option[Instr[Ctx]] = method.instrs.get(pc)

  def advancePC(): Unit = pc += 1

  def offsetPC(offset: Int): ExeResult[Unit] = {
    val newPC = pc + offset
    if (newPC >= 0 && newPC < method.instrs.length) {
      pc = newPC
      Right(())
    } else Left(InvalidInstrOffset)
  }

  def complete(): Unit = pc = method.instrs.length

  def isComplete: Boolean = pc == method.instrs.length

  def push(v: Val): ExeResult[Unit] = opStack.push(v)

  def pop(): ExeResult[Val] = opStack.pop()

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def popT[T <: Val](): ExeResult[T] = pop().flatMap { elem =>
    try Right(elem.asInstanceOf[T])
    catch {
      case _: ClassCastException => Left(InvalidType(elem))
    }
  }

  def getLocal(index: Int): ExeResult[Val] = {
    if (locals.isDefinedAt(index)) Right(locals(index)) else Left(InvalidLocalIndex)
  }

  def setLocal(index: Int, v: Val): ExeResult[Unit] = {
    if (!locals.isDefinedAt(index)) {
      Left(InvalidLocalIndex)
    } else if (locals(index).tpe != v.tpe) {
      Left(InvalidLocalType)
    } else {
      Right(locals.update(index, v))
    }
  }

  def getField(index: Int): ExeResult[Val] = {
    val fields = obj.fields
    if (fields.isDefinedAt(index)) Right(fields(index)) else Left(InvalidFieldIndex)
  }

  def setField(index: Int, v: Val): ExeResult[Unit] = {
    val fields = obj.fields
    if (!fields.isDefinedAt(index)) {
      Left(InvalidFieldIndex)
    } else if (fields(index).tpe != v.tpe) {
      Left(InvalidFieldType)
    } else {
      Right(fields.update(index, v))
    }
  }

  def updateState(): ExeResult[Unit] = {
    obj.addressOpt match {
      case Some(address) => ctx.updateState(address, AVector.from(obj.fields))
      case None          => Right(())
    }
  }

  private def getMethod(index: Int): ExeResult[Method[Ctx]] = {
    obj.getMethod(index).toRight(InvalidMethodIndex(index))
  }

  def methodFrame(index: Int): ExeResult[Frame[Ctx]] = {
    for {
      method <- getMethod(index)
      args   <- opStack.pop(method.localsType.length)
      _      <- method.check(args)
    } yield Frame.build(ctx, obj, method, args, opStack.push)
  }

  @tailrec
  final def execute(): ExeResult[Unit] = {
    currentInstr match {
      case Some(instr) =>
        // No flatMap for tailrec
        instr.runWith(this) match {
          case Right(_) =>
            advancePC()
            execute()
          case Left(e) => Left(e)
        }
      case None =>
        updateState()
    }
  }
}

object Frame {
  def build[Ctx <: Context](ctx: Ctx,
                            obj: ScriptObj[Ctx],
                            args: AVector[Val],
                            returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx] =
    build(ctx, obj, 0, args: AVector[Val], returnTo)

  def build[Ctx <: Context](ctx: Ctx,
                            obj: ContractObj[Ctx],
                            methodIndex: Int,
                            args: AVector[Val],
                            returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx] = {
    val method = obj.code.methods(methodIndex)
    build(ctx, obj, method, args, returnTo)
  }

  private[Frame] def build[Ctx <: Context](
      ctx: Ctx,
      obj: ContractObj[Ctx],
      method: Method[Ctx],
      args: AVector[Val],
      returnTo: AVector[Val] => ExeResult[Unit]): Frame[Ctx] = {
    val locals = method.localsType.mapToArray(_.default)
    args.foreachWithIndex((v, index) => locals(index) = v)
    new Frame[Ctx](0, obj, Stack.ofCapacity(opStackMaxSize), method, locals, returnTo, ctx)
  }

  def externalMethodFrame[C <: StatefulContext](
      frame: Frame[C],
      contractKey: ALF.Hash,
      index: Int
  ): ExeResult[Frame[StatefulContext]] = {
    for {
      contractObj <- frame.ctx.worldState
        .getContractObj(contractKey)
        .left
        .map[ExeFailure](IOErrorLoadContract)
      method <- contractObj.getMethod(index).toRight[ExeFailure](InvalidMethodIndex(index))
      args   <- frame.opStack.pop(method.localsType.length)
      _      <- method.check(args)
    } yield Frame.build(frame.ctx, contractObj, method, args, frame.opStack.push)
  }
}
