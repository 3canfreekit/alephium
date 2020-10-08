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

package org.alephium.flow.setting

import org.alephium.protocol.ALF
import org.alephium.protocol.model.{Address, NetworkType}
import org.alephium.protocol.model.NetworkType.{Devnet, Mainnet, Testnet}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{AVector, U64}

@SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
object Genesis {
  def apply(networkType: NetworkType): AVector[(LockupScript, U64)] =
    networkType match {
      case Mainnet => mainnet
      case Devnet  => devnet
      case Testnet => testnet
    }

  // scalastyle:off magic.number
  private val mainnet: AVector[(LockupScript, U64)] = AVector(
    build0("M1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3", ALF.alf(U64.Million).get, Mainnet),
    build0("M1H7CmpbvGJwgyLzR91wzSJJSkiBC92WDPTWny4gmhQJQc", ALF.alf(U64.Million).get, Mainnet),
    build0("M1DkrQMni2h8KYpvY8t7dECshL66gwnxiR5uD2Udxps6og", ALF.alf(U64.Million).get, Mainnet),
    build0("M131R8ufDhcsu6SRztR9D3m8GUzkWFUPfT78aQ6jgtgzob", ALF.alf(U64.Million).get, Mainnet)
  )

  private val devnet: AVector[(LockupScript, U64)] = AVector(
    build1("D15eh7Qe3CC9YgQcY3bpfZ9z6mSsU1mDuKKY4ox5ZNx51E", 100, Devnet),
    build1("D14GrSzgcEcJVRCjA8WE6VPaagr4csBvbuQUfpaCfDJnnF", 100, Devnet),
    build1("D13zsJa9zidziyzjxkwgsVePxBbgb1hBUCdYyoRRHR8epm", 100, Devnet),
    build1("D1AujpupFP4KWeZvqA7itsHY9cLJmx4qTzojVZrg8W9y9n", 100, Devnet)
  )

  private val testnet: AVector[(LockupScript, U64)] = AVector(
    build1("T1J9XcQ5FsFfihNYMzdYKXoiZBTzsHQifzu7CKQfZPbwt1", 100, Testnet),
    build1("T16Q9sJkSYW66HKeai8sJeEo2buKLdwnmvY7VXtZFVDCoT", 100, Testnet),
    build1("T15phYy54YWvsLbnUcn9xQAp82PgKXWRKfFUmDUYC13Ecm", 100, Testnet),
    build1("T17ad4SSso1f3trkUfmi1YHkNnEo7qnF6SA83tdNJD2Saa", 100, Testnet)
  )
  // scalastyle:on magic.number

  private def build0(addressRaw: String,
                     amount: U64,
                     networkType: NetworkType): (LockupScript, U64) = {
    val address = Address
      .fromBase58(addressRaw, networkType)
      .getOrElse(throw new RuntimeException(s"Invalid address $addressRaw for $networkType"))
    (address.lockupScript, amount)
  }

  private def build1(addressRaw: String,
                     amount: Long,
                     networkType: NetworkType): (LockupScript, U64) = {
    build0(addressRaw, U64.unsafe(amount), networkType)
  }
}
