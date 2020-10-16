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

import scala.collection.mutable

import org.alephium.flow.core.BlockFlow
import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.{ALF, Hash, Signature, SignatureSchema}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model._
import org.alephium.protocol.vm._
import org.alephium.serde.serialize
import org.alephium.util.{AVector, EitherF, U256}

trait NonCoinbaseValidation {
  import ValidationStatus._

  implicit def groupConfig: GroupConfig

  def validateMempoolTx(tx: Transaction, flow: BlockFlow): IOResult[TxStatus] = {
    val validationResult = for {
      _          <- checkStateless(tx)
      worldState <- from(flow.getBestPersistedTrie(tx.chainIndex.from))
      _          <- checkStateful(tx, worldState)
    } yield ()
    convert(validationResult, ValidTx)
  }
  protected[validation] def checkBlockTx(tx: Transaction,
                                         worldState: WorldState): TxValidationResult[Unit] = {
    for {
      _ <- checkStateless(tx)
      _ <- checkStateful(tx, worldState)
    } yield ()
  }

  protected[validation] def checkStateless(tx: Transaction): TxValidationResult[ChainIndex] = {
    for {
      _          <- checkInputNum(tx)
      _          <- checkOutputNum(tx)
      _          <- checkAlfOutputAmount(tx)
      chainIndex <- checkChainIndex(tx)
      _          <- checkUniqueInputs(tx)
      _          <- checkOutputDataSize(tx)
    } yield chainIndex
  }
  protected[validation] def checkStateful(tx: Transaction,
                                          worldState: WorldState): TxValidationResult[Unit] = {
    for {
      preOutputs <- getPreOutputs(tx, worldState)
      _          <- checkAlfBalance(tx, preOutputs)
      _          <- checkTokenBalance(tx, preOutputs)
      _          <- checkWitnesses(tx, preOutputs)
      _          <- checkTxScript(tx, worldState)
    } yield ()
  }

  protected[validation] def getPreOutputs(
      tx: Transaction,
      worldState: WorldState): TxValidationResult[AVector[TxOutput]]

  // format off for the sake of reading and checking rules
  // format: off
  protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkOutputNum(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkAlfOutputAmount(tx: Transaction): TxValidationResult[U256]
  protected[validation] def checkChainIndex(tx: Transaction): TxValidationResult[ChainIndex]
  protected[validation] def checkUniqueInputs(tx: Transaction): TxValidationResult[Unit]
  protected[validation] def checkOutputDataSize(tx: Transaction): TxValidationResult[Unit]

  protected[validation] def checkAlfBalance(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkTokenBalance(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkWitnesses(tx: Transaction, preOutputs: AVector[TxOutput]): TxValidationResult[Unit]
  protected[validation] def checkTxScript(tx: Transaction, worldState: WorldState): TxValidationResult[Unit] // TODO: optimize it with preOutputs
  // format: on
}

// Note: only non-coinbase transactions are validated here
object NonCoinbaseValidation {
  import ValidationStatus._

  def build(implicit groupConfig: GroupConfig): NonCoinbaseValidation = new Impl()

  class Impl(implicit val groupConfig: GroupConfig) extends NonCoinbaseValidation {
    protected[validation] def checkInputNum(tx: Transaction): TxValidationResult[Unit] = {
      val inputNum = tx.unsigned.inputs.length
      if (inputNum == 0) invalidTx(NoInputs)
      else if (inputNum > ALF.MaxTxInputNum) invalidTx(TooManyInputs)
      else validTx(())
    }

    protected[validation] def checkOutputNum(tx: Transaction): TxValidationResult[Unit] = {
      val outputNum = tx.outputsLength
      if (outputNum == 0) invalidTx(NoOutputs)
      else if (outputNum > ALF.MaxTxOutputNum) invalidTx(TooManyOutputs)
      else validTx(())
    }

    protected[validation] def checkAlfOutputAmount(tx: Transaction): TxValidationResult[U256] = {
      tx.alfAmountInOutputs match {
        case Some(total) => validTx(total)
        case None        => invalidTx(BalanceOverFlow)
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    protected[validation] def checkChainIndex(tx: Transaction): TxValidationResult[ChainIndex] = {
      val inputIndexes = tx.unsigned.inputs.map(_.fromGroup).toSet
      if (inputIndexes.size != 1) invalidTx(InvalidInputGroupIndex)
      else {
        val fromIndex = inputIndexes.head
        val outputIndexes =
          (0 until tx.outputsLength).view
            .map(index => getToGroup(tx.getOutput(index), fromIndex))
            .filter(_ != fromIndex)
            .toSet
        outputIndexes.size match {
          case 0 => validTx(ChainIndex(fromIndex, fromIndex))
          case 1 => validTx(ChainIndex(fromIndex, outputIndexes.head))
          case _ => invalidTx(InvalidOutputGroupIndex)
        }
      }
    }

    private def getToGroup(output: TxOutput, fromIndex: GroupIndex): GroupIndex = output match {
      case o: AssetOutput    => o.toGroup
      case _: ContractOutput => fromIndex
    }

    protected[validation] def checkUniqueInputs(tx: Transaction): TxValidationResult[Unit] = {
      val utxoUsed = scala.collection.mutable.Set.empty[TxOutputRef]
      tx.unsigned.inputs.foreachE { input =>
        if (utxoUsed.contains(input.outputRef)) invalidTx(DoubleSpending)
        else {
          utxoUsed += input.outputRef
          validTx(())
        }
      }
    }

    protected[validation] def checkOutputDataSize(tx: Transaction): TxValidationResult[Unit] = {
      EitherF.foreachTry(0 until tx.outputsLength) { outputIndex =>
        tx.getOutput(outputIndex) match {
          case output: AssetOutput =>
            if (output.additionalData.length > ALF.MaxOutputDataSize)
              invalidTx(OutputDataSizeExceeded)
            else Right(())
          case _ => Right(())
        }
      }
    }

    protected[validation] def getPreOutputs(
        tx: Transaction,
        worldState: WorldState): TxValidationResult[AVector[TxOutput]] = {
      worldState.getPreOutputs(tx) match {
        case Right(preOutputs)            => validTx(preOutputs)
        case Left(IOError.KeyNotFound(_)) => invalidTx(NonExistInput)
        case Left(error)                  => Left(Left(error))
      }
    }

    protected[validation] def checkAlfBalance(
        tx: Transaction,
        preOutputs: AVector[TxOutput]): TxValidationResult[Unit] = {
      val inputSum = preOutputs.fold(U256.Zero)(_ addUnsafe _.amount)
      tx.alfAmountInOutputs match {
        case Some(outputSum) if outputSum <= inputSum => validTx(())
        case Some(_)                                  => invalidTx(InvalidAlfBalance)
        case None                                     => invalidTx(BalanceOverFlow)
      }
    }

    protected[validation] def checkTokenBalance(
        tx: Transaction,
        preOutputs: AVector[TxOutput]): TxValidationResult[Unit] = {
      for {
        inputBalances  <- computeTokenBalances(preOutputs)
        outputBalances <- computeTokenBalances(tx.allOutputs)
        _ <- {
          val ok = outputBalances.forall {
            case (tokenId, balance) =>
              (inputBalances.contains(tokenId) && inputBalances(tokenId) >= balance)
          }
          if (ok) validTx(()) else invalidTx(InvalidTokenBalance)
        }
      } yield ()
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    protected[validation] def computeTokenBalances(
        outputs: AVector[TxOutput]): TxValidationResult[mutable.Map[TokenId, U256]] =
      try {
        val balances = mutable.Map.empty[TokenId, U256]
        outputs.foreach { output =>
          output.tokens.foreach {
            case (tokenId, amount) =>
              val total = balances.getOrElse(tokenId, U256.Zero)
              balances.put(tokenId, total.add(amount).get)
          }
        }
        Right(balances)
      } catch {
        case _: NoSuchElementException => Left(Right(BalanceOverFlow))
      }

    // TODO: signatures might not be 1-to-1 mapped to inputs
    protected[validation] def checkWitnesses(
        tx: Transaction,
        preOutputs: AVector[TxOutput]): TxValidationResult[Unit] = {
      assume(tx.unsigned.inputs.length == preOutputs.length)
      val signatures = Stack.unsafe(tx.signatures.reverse, tx.signatures.length)
      EitherF.foreachTry(preOutputs.indices) { idx =>
        val unlockScript = tx.unsigned.inputs(idx).unlockScript
        checkLockupScript(tx, preOutputs(idx).lockupScript, unlockScript, signatures)
      }
    }

    protected[validation] def checkLockupScript(
        tx: Transaction,
        lockupScript: LockupScript,
        unlockScript: UnlockScript,
        signatures: Stack[Signature]): TxValidationResult[Unit] = {
      (lockupScript, unlockScript) match {
        case (lock: LockupScript.P2PKH, unlock: UnlockScript.P2PKH) =>
          checkP2pkh(tx, lock, unlock, signatures)
        case (lock: LockupScript.P2SH, unlock: UnlockScript.P2SH) =>
          checkP2SH(tx, lock, unlock, signatures)
        case (lock: LockupScript.P2S, unlock: UnlockScript.P2S) =>
          checkP2S(tx, lock, unlock, signatures)
        case _ =>
          invalidTx(InvalidUnlockScriptType)
      }
    }

    protected[validation] def checkP2pkh(tx: Transaction,
                                         lock: LockupScript.P2PKH,
                                         unlock: UnlockScript.P2PKH,
                                         signatures: Stack[Signature]): TxValidationResult[Unit] = {
      if (Hash.hash(unlock.publicKey.bytes) != lock.pkHash) {
        invalidTx(InvalidPublicKeyHash)
      } else {
        signatures.pop() match {
          case Right(signature) =>
            if (!SignatureSchema.verify(tx.hash.bytes, signature, unlock.publicKey)) {
              invalidTx(InvalidSignature)
            } else validTx(())
          case Left(_) => invalidTx(NotEnoughSignature)
        }
      }
    }

    protected[validation] def checkP2SH(tx: Transaction,
                                        lock: LockupScript.P2SH,
                                        unlock: UnlockScript.P2SH,
                                        signatures: Stack[Signature]): TxValidationResult[Unit] = {
      if (Hash.hash(serialize(unlock.script)) != lock.scriptHash) {
        invalidTx(InvalidScriptHash)
      } else {
        checkScript(tx, unlock.script, unlock.params, signatures)
      }
    }

    protected[validation] def checkP2S(tx: Transaction,
                                       lock: LockupScript.P2S,
                                       unlock: UnlockScript.P2S,
                                       signatures: Stack[Signature]): TxValidationResult[Unit] = {
      checkScript(tx, lock.script, unlock.params, signatures)
    }

    protected[validation] def checkScript(
        tx: Transaction,
        script: StatelessScript,
        params: AVector[Val],
        signatures: Stack[Signature]): TxValidationResult[Unit] = {
      StatelessVM.runAssetScript(tx.hash, script, params, signatures) match {
        case Right(_) => validTx(()) // TODO: handle returns
        case Left(e)  => invalidTx(InvalidUnlockScript(e))
      }
    }

    protected[validation] def checkTxScript(tx: Transaction,
                                            worldState: WorldState): TxValidationResult[Unit] = {
      val chainIndex = tx.chainIndex
      if (chainIndex.isIntraGroup) {
        tx.unsigned.scriptOpt match {
          case Some(script) =>
            StatefulVM.runTxScript(worldState, tx, script) match {
              case Right(StatefulVM.TxScriptExecution(contractInputs, generatedOutputs, _)) =>
                if (contractInputs != tx.contractInputs) invalidTx(InvalidContractInputs)
                else if (generatedOutputs != tx.generatedOutputs) invalidTx(InvalidGeneratedOutputs)
                else validTx(())
              case Left(error) => invalidTx(TxScriptExeFailed(error))
            }
          case None => validTx(())
        }
      } else {
        if (tx.unsigned.scriptOpt.nonEmpty) invalidTx(UnexpectedTxScript) else validTx(())
      }
    }
  }
}
