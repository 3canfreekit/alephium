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

package org.alephium.util

import java.security.SecureRandom

import scala.annotation.tailrec
import scala.util.Random

object UnsecureRandom {
  val source: Random = Random

  @tailrec
  def nextNonZeroInt(): Int = {
    val random = source.nextInt()
    if (random != 0) random else nextNonZeroInt()
  }

  def nextNonNegative(): Int = {
    source.nextInt(Int.MaxValue)
  }

  def nextU256(): U256 = {
    val buffer = new Array[Byte](32)
    source.nextBytes(buffer)
    U256.unsafe(buffer)
  }

  def nextI256(): I256 = {
    val buffer = new Array[Byte](32)
    source.nextBytes(buffer)
    I256.unsafe(buffer)
  }

  def nextU256NonUniform(bound: U256): U256 = {
    nextU256().modUnsafe(bound)
  }

}

object SecureAndSlowRandom {
  val source: SecureRandom = new SecureRandom()

  @tailrec
  def nextNonZeroInt(): Int = {
    val random = source.nextInt()
    if (random != 0) random else nextNonZeroInt()
  }

  def nextNonNegative(): Int = {
    source.nextInt(Int.MaxValue)
  }

  def nextU256(): U256 = {
    val buffer = new Array[Byte](32)
    source.nextBytes(buffer)
    U256.unsafe(buffer)
  }

  def nextI256(): I256 = {
    val buffer = new Array[Byte](32)
    source.nextBytes(buffer)
    I256.unsafe(buffer)
  }

  def nextU256NonUniform(bound: U256): U256 = {
    nextU256().modUnsafe(bound)
  }
}
