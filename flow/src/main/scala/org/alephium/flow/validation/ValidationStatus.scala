package org.alephium.flow.validation

import org.alephium.io.{IOError, IOResult}
import org.alephium.protocol.ALF.Hash
import org.alephium.protocol.vm.ExeFailure
import org.alephium.util.AVector

// scalastyle:off number.of.types

sealed trait ValidationStatus
sealed trait InvalidStatus extends ValidationStatus
sealed trait ValidStatus   extends ValidationStatus

sealed trait BlockStatus        extends ValidationStatus
sealed trait InvalidBlockStatus extends BlockStatus with InvalidStatus
final case object ValidBlock    extends BlockStatus with ValidStatus

sealed trait HeaderStatus        extends ValidationStatus
sealed trait InvalidHeaderStatus extends HeaderStatus with InvalidBlockStatus
final case object ValidHeader    extends HeaderStatus with ValidStatus

// TBD: final case object InvalidBlockSize               extends InvalidBlockStatus
final case object InvalidGroup                      extends InvalidBlockStatus
final case object InvalidGenesisTimeStamp           extends InvalidHeaderStatus
final case object InvalidGenesisDeps                extends InvalidHeaderStatus
final case object InvalidGenesisWorkTarget          extends InvalidHeaderStatus
final case object NoIncreasingTimeStamp             extends InvalidHeaderStatus
final case object TooAdvancedTimeStamp              extends InvalidHeaderStatus
final case object InvalidTimeStamp                  extends InvalidHeaderStatus
final case object InvalidWorkAmount                 extends InvalidHeaderStatus
final case object InvalidWorkTarget                 extends InvalidHeaderStatus
final case class MissingDeps(hashes: AVector[Hash]) extends InvalidHeaderStatus
final case class HeaderIOError(e: IOError)          extends InvalidHeaderStatus
final case object EmptyTransactionList              extends InvalidBlockStatus
final case object InvalidCoinbase                   extends InvalidBlockStatus
final case object InvalidMerkleRoot                 extends InvalidBlockStatus
final case class ExistInvalidTx(e: InvalidTxStatus) extends InvalidBlockStatus

object ValidationStatus {
  private[validation] def invalidHeader[T](status: InvalidHeaderStatus): HeaderValidationResult[T] =
    Left(Right(status))
  private[validation] def invalidBlock[T](status: InvalidBlockStatus): BlockValidationResult[T] =
    Left(Right(status))
  private[validation] def invalidTx[T](status: InvalidTxStatus): TxValidationResult[T] =
    Left(Right(status))
  private[validation] def validHeader[T](t: T): HeaderValidationResult[T] = Right(t)
  private[validation] def validBlock[T](t: T): BlockValidationResult[T]   = Right(t)
  private[validation] def validTx[T](t: T): TxValidationResult[T]         = Right(t)

  private[validation] def from[Invalid, T](
      result: IOResult[T]): Either[Either[IOError, Invalid], T] = {
    result match {
      case Right(t)    => Right(t)
      case Left(error) => Left(Left(error))
    }
  }

  private[validation] def convert[T](x: Either[Either[IOError, T], Unit], default: T): IOResult[T] =
    x match {
      case Left(Left(error)) => Left(error)
      case Left(Right(t))    => Right(t)
      case Right(())         => Right(default)
    }

  private[validation] def convert[T](x: TxValidationResult[T]): BlockValidationResult[T] =
    x match {
      case Left(Left(error)) => Left(Left(error))
      case Left(Right(t))    => Left(Right(ExistInvalidTx(t)))
      case Right(t)          => Right(t)
    }
}

sealed trait TxStatus        extends ValidationStatus
sealed trait InvalidTxStatus extends TxStatus with InvalidStatus
final case object ValidTx    extends TxStatus with ValidStatus

final case object NoInputs                              extends InvalidTxStatus
final case object TooManyInputs                         extends InvalidTxStatus
final case object NoOutputs                             extends InvalidTxStatus
final case object TooManyOutputs                        extends InvalidTxStatus
final case object DuplicatedInputs                      extends InvalidTxStatus
final case object InvalidInputGroupIndex                extends InvalidTxStatus
final case object InvalidOutputGroupIndex               extends InvalidTxStatus
final case object DoubleSpending                        extends InvalidTxStatus
final case object OutputDataSizeExceeded                extends InvalidTxStatus
final case object NonExistInput                         extends InvalidTxStatus
final case object InvalidAlfBalance                     extends InvalidTxStatus
final case object InvalidTokenBalance                   extends InvalidTxStatus
final case object BalanceOverFlow                       extends InvalidTxStatus
final case object InvalidWitnessLength                  extends InvalidTxStatus
final case object InvalidPublicKeyHash                  extends InvalidTxStatus
final case object InvalidScriptHash                     extends InvalidTxStatus
final case object InvalidSignature                      extends InvalidTxStatus
final case object NotEnoughSignature                    extends InvalidTxStatus
final case object InvalidUnlockScriptType               extends InvalidTxStatus
final case class InvalidUnlockScript(error: ExeFailure) extends InvalidTxStatus
final case object CreateContractWithOldId               extends InvalidTxStatus
final case class WorldStateIOError(error: IOError)      extends InvalidTxStatus
