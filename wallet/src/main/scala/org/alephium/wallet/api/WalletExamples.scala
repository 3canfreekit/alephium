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

package org.alephium.wallet.api

import sttp.tapir.EndpointIO.Example

import org.alephium.api.EndpointsExamples
import org.alephium.api.model.Amount
import org.alephium.crypto.wallet.Mnemonic
import org.alephium.protocol.PublicKey
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{defaultGasPrice, minimalGas}
import org.alephium.util.{AVector, Hex}
import org.alephium.wallet.api.model._

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
trait WalletExamples extends EndpointsExamples {
  private val password = "my-secret-password"
  private val mnemonic =
    Mnemonic
      .from(
        "vault alarm sad mass witness property virus style good flower rice alpha viable evidence run glare pretty scout evil judge enroll refuse another lava"
      )
      .get

  implicit private val groupConfig =
    new GroupConfig {
      override def groups: Int = 4
    }

  private val walletName         = "wallet-super-name"
  private val mnemonicPassphrase = "optional-mnemonic-passphrase"
  private val fromGroup          = 2
  private val toGroup            = 1
  private val publicKey = PublicKey
    .from(Hex.unsafe("02febe3a7b4035057c2cfbe2f89de2e86becc9821ae149e0044875a031e1d102b7"))
    .get
  val mnemonicSizes: String = Mnemonic.Size.list.toSeq.map(_.value).mkString(", ")

  implicit val walletCreationExamples: List[Example[WalletCreation]] = List(
    moreSettingsExample(WalletCreation(password, walletName, None, None, None), "User"),
    moreSettingsExample(
      WalletCreation(
        password,
        walletName,
        Some(true),
        None,
        Some(Mnemonic.Size.list.last)
      ),
      "Miner (w/o pass phrase)"
    ),
    moreSettingsExample(
      WalletCreation(
        password,
        walletName,
        Some(true),
        Some(mnemonicPassphrase),
        Some(Mnemonic.Size.list.last)
      ),
      "Miner (with pass phrase)"
    )
  )
  implicit val walletCreationResultExamples: List[Example[WalletCreation.Result]] =
    simpleExample(WalletCreation.Result(walletName, mnemonic))

  implicit val walletRestoreExamples: List[Example[WalletRestore]] =
    List(
      moreSettingsExample(
        WalletRestore(password, mnemonic, walletName, None, None),
        "User"
      ),
      moreSettingsExample(
        WalletRestore(password, mnemonic, walletName, Some(true), None),
        "Miner (w/o pass phrase)"
      ),
      moreSettingsExample(
        WalletRestore(password, mnemonic, walletName, Some(true), Some(mnemonicPassphrase)),
        "Miner (with pass phrase)"
      )
    )

  implicit val walletRestoreResultExamples: List[Example[WalletRestore.Result]] =
    simpleExample(WalletRestore.Result(walletName))

  implicit val walletStatusExamples: List[Example[WalletStatus]] =
    simpleExample(WalletStatus(walletName, locked = true))

  implicit val walletsStatusExamples: List[Example[AVector[WalletStatus]]] =
    simpleExample(AVector(WalletStatus(walletName, locked = true)))

  implicit val walletUnlockExamples: List[Example[WalletUnlock]] =
    List(
      defaultExample(WalletUnlock(password, None)),
      moreSettingsExample(
        WalletUnlock(password, Some(mnemonicPassphrase)),
        "More Settings (with pass phrase)"
      )
    )

  implicit val walletDeletionExamples: List[Example[WalletDeletion]] =
    simpleExample(WalletDeletion(password))

  implicit val balancesExamples: List[Example[Balances]] =
    simpleExample(
      Balances(
        balance,
        balance.hint,
        AVector(
          Balances.AddressBalance(
            address,
            balance,
            balance.hint,
            Amount.Zero,
            Amount.Zero.hint,
            None
          )
        )
      )
    )

  implicit val revealMnemonicExamples: List[Example[RevealMnemonic]] =
    simpleExample(RevealMnemonic(password))

  implicit val revealMnemonicResultExamples: List[Example[RevealMnemonic.Result]] =
    simpleExample(RevealMnemonic.Result(mnemonic))

  implicit val transferExamples: List[Example[Transfer]] = List(
    defaultExample(Transfer(defaultDestinations)),
    moreSettingsExample(
      Transfer(
        moreSettingsDestinations,
        Some(minimalGas),
        Some(defaultGasPrice),
        Some(defaultUtxosLimit)
      )
    )
  )

  implicit val signTransactionExamples: List[Example[Sign]] =
    simpleExample(Sign(hexString))

  implicit val signTransactionResultExamples: List[Example[Sign.Result]] =
    simpleExample(Sign.Result(signature))

  implicit val sweepAllExamples: List[Example[SweepAll]] =
    List(
      defaultExample(SweepAll(address)),
      moreSettingsExample(
        SweepAll(
          address,
          Some(ts),
          Some(minimalGas),
          Some(defaultGasPrice),
          Some(defaultUtxosLimit)
        )
      )
    )

  implicit val transferResultExamples: List[Example[Transfer.Result]] =
    simpleExample(Transfer.Result(txId, fromGroup, toGroup))

  implicit val addressesExamples: List[Example[Addresses]] =
    simpleExample(Addresses(address, AVector(Addresses.Info(address, 0))))

  implicit val addressInfoExamples: List[Example[AddressInfo]] =
    simpleExample(AddressInfo(address, publicKey, address.groupIndex.value))

  implicit val minerAddressInfoExamples: List[Example[MinerAddressInfo]] =
    simpleExample(MinerAddressInfo(address, fromGroup))

  implicit val minerAddressesInfoExample: List[Example[AVector[MinerAddressesInfo]]] =
    simpleExample(AVector(MinerAddressesInfo(AVector(MinerAddressInfo(address, fromGroup)))))

  implicit val addressessInfoExamples: List[Example[AVector[MinerAddressInfo]]] =
    simpleExample(AVector(MinerAddressInfo(address, fromGroup)))

  implicit val changeActiveAddressExamples: List[Example[ChangeActiveAddress]] =
    simpleExample(ChangeActiveAddress(address))

  implicit val deriveNextAddressResultExamples: List[Example[DeriveNextAddress.Result]] =
    simpleExample(DeriveNextAddress.Result(address))
}
