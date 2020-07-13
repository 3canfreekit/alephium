package org.alephium.protocol.vm.lang

import scala.collection.{immutable, mutable}

import fastparse.Parsed

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Ast.MultiContract

object Compiler {
  def compile(input: String): Either[Error, StatelessScript] =
    try {
      fastparse.parse(input, Parser.contract(_)) match {
        case Parsed.Success(contract, _) =>
          val state = State.buildFor(contract)
          Right(contract.genCode(state))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  def compileOneOf(input: String, index: Int): Either[Error, StatelessScript] =
    try {
      fastparse.parse(input, Parser.multiContract(_)) match {
        case Parsed.Success(multiContract, _) =>
          val state = State.buildFor(multiContract, index)
          Right(multiContract.genCode(state, index))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  trait FuncInfo {
    def name: String
    def getReturnType(inputType: Seq[Type]): Seq[Type]
    def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]]
    def genCode(objId: Ast.Ident): Seq[Instr[StatelessContext]]
  }

  final case class Error(message: String) extends Exception(message)
  object Error {
    def parse(failure: Parsed.Failure): Error = Error(s"Parser failed: $failure")
  }

  def expectOneType(ident: Ast.Ident, tpe: Seq[Type]): Type = {
    if (tpe.length == 1) tpe(0)
    else throw Error(s"Try to set types $tpe for varialbe $ident")
  }

  final case class VarInfo(tpe: Type, isMutable: Boolean, index: Byte)
  class SimpleFunc(val id: Ast.FuncId, argsType: Seq[Type], val returnType: Seq[Type], index: Byte)
      extends FuncInfo {
    def name: String = id.name

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) returnType
      else throw Error(s"Invalid args type $inputType for builtin func $name")
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      Seq(CallLocal(index))
    }

    override def genCode(objId: Ast.Ident): Seq[Instr[StatelessContext]] = {
      Seq(CallExternal(index))
    }
  }
  object SimpleFunc {
    def from(funcs: Seq[Ast.FuncDef]): Seq[SimpleFunc] = {
      funcs.view.zipWithIndex.map {
        case (func, index) =>
          new SimpleFunc(func.id, func.args.map(_.tpe), func.rtypes, index.toByte)
      }.toSeq
    }
  }

  object State {
    def buildFor(contract: Ast.Contract): State =
      State(mutable.HashMap.empty,
            Ast.FuncId.empty,
            0,
            contract.funcTable,
            immutable.Map(contract.ident -> contract.funcTable))

    def buildFor(multiContract: MultiContract, contractIndex: Int): State = {
      val contractTable = multiContract.contracts.map(c => c.ident -> c.funcTable).toMap
      State(mutable.HashMap.empty,
            Ast.FuncId.empty,
            0,
            multiContract.get(contractIndex).funcTable,
            contractTable)
    }
  }

  final case class State(
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, SimpleFunc],
      contractTable: immutable.Map[Ast.TypeId, immutable.Map[Ast.FuncId, SimpleFunc]]) {
    def setFuncScope(funcId: Ast.FuncId): Unit = {
      scope    = funcId
      varIndex = 0
    }

    def addVariable(ident: Ast.Ident, tpe: Seq[Type], isMutable: Boolean): Unit = {
      addVariable(ident, expectOneType(ident, tpe), isMutable)
    }

    private def scopedName(name: String): String = {
      if (scope == Ast.FuncId.empty) name else s"${scope.name}.$name"
    }

    def addVariable(ident: Ast.Ident, tpe: Type, isMutable: Boolean): Unit = {
      val name  = ident.name
      val sname = scopedName(name)
      if (varTable.contains(name)) {
        throw Error(s"Global variable has the same name as local variable: $name")
      } else if (varTable.contains(sname)) {
        throw Error(s"Local variables have the same name: $name")
      } else if (varIndex >= 0xFF) {
        throw Error(s"Number of variables more than ${0xFF}")
      } else {
        val varType = tpe match {
          case c: Type.Contract => Type.Contract.local(c.id, ident)
          case _                => tpe
        }
        varTable(sname) = VarInfo(varType, isMutable, varIndex.toByte)
        varIndex += 1
      }
    }

    def getVariable(ident: Ast.Ident): VarInfo = {
      val name  = ident.name
      val sname = scopedName(ident.name)
      varTable.getOrElse(
        sname,
        varTable.getOrElse(name, throw Error(s"Variable $sname does not exist"))
      )
    }

    def getLocalVars(func: Ast.FuncId): Seq[VarInfo] = {
      varTable.view.filterKeys(_.startsWith(func.name)).values.toSeq.sortBy(_.index)
    }

    def genCode(ident: Ast.Ident): Instr[StatelessContext] = {
      val varInfo = getVariable(ident)
      if (isField(ident)) StoreField(varInfo.index.toByte)
      else StoreLocal(varInfo.index.toByte)
    }

    def isField(ident: Ast.Ident): Boolean = varTable.contains(ident.name)

    def getType(ident: Ast.Ident): Type = getVariable(ident).tpe

    def getFunc(call: Ast.FuncId): FuncInfo = {
      if (call.isBuiltIn) getBuiltInFunc(call)
      else getNewFunc(call)
    }

    def getContract(objId: Ast.Ident): Ast.TypeId = {
      getVariable(objId).tpe match {
        case c: Type.Contract => c.id
        case _                => throw Error(s"Invalid contract object id ${objId.name}")
      }
    }

    def getFunc(objId: Ast.Ident, callId: Ast.FuncId): FuncInfo = {
      val contract = getContract(objId)
      contractTable(contract)
        .getOrElse(callId, throw Error(s"Function ${objId.name}.${callId.name} does not exist"))
    }

    private def getBuiltInFunc(call: Ast.FuncId): FuncInfo = {
      BuiltIn.funcs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    private def getNewFunc(call: Ast.FuncId): FuncInfo = {
      funcIdents.getOrElse(call, throw Error(s"Function ${call.name} does not exist"))
    }

    def checkAssign(ident: Ast.Ident, tpe: Seq[Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) throw Error(s"Assign $tpe value to $ident: ${varInfo.tpe}")
      if (!varInfo.isMutable) throw Error(s"Assign value to immutable variable $ident")
    }

    def checkReturn(returnType: Seq[Type]): Unit = {
      val rtype = funcIdents(scope).returnType
      if (returnType != rtype)
        throw Compiler.Error(s"Invalid return types: expected $rtype, got $returnType")
    }
  }
}
