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

package org.alephium.protocol.model

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.serde._
import org.alephium.util.{Bytes, Hex, Number}

/*
 * value = mantissa * 256 ^ (exponent - 3)
 * value should not be negative always
 */
final case class Target(bits: ByteString) extends Ordered[Target] {
  lazy val value: BigInteger = Target.fromCompactBitsUnsafe(bits)

  override def compare(that: Target): Int = this.value.compareTo(that.value)

  def toHexString: String = Hex.toHexString(bits)

  override def toString: String = s"Target($toHexString)"
}

object Target {
  implicit val serde: Serde[Target] = Serde.bytesSerde(4).xmap(unsafe, _.bits)

  val maxBigInt: BigInteger = BigInteger.ONE.shiftLeft(256)

  val Max: Target = unsafe(maxBigInt.subtract(BigInteger.ONE))

  def unsafe(byteString: ByteString): Target = {
    require(byteString.length == 4)
    new Target(byteString)
  }

  def unsafe(value: BigInteger): Target = {
    require(Number.nonNegative(value) && value.compareTo(maxBigInt) < 0)
    new Target(toCompactBitsUnsafe(value))
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def fromCompactBitsUnsafe(bits: ByteString): BigInteger = {
    assume(bits.length == 4)
    val size: Int            = Bytes.toPosInt(bits(0))
    val mantissa: BigInteger = new BigInteger(1, bits.tail.toArray)
    if (size <= 3) {
      mantissa.shiftRight(8 * (3 - size))
    } else {
      mantissa.shiftLeft(8 * (size - 3))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  def toCompactBitsUnsafe(value: BigInteger): ByteString = {
    val size: Int = (value.bitLength() + 7) / 8
    val mantissa = if (size <= 3) {
      Bytes.from(value.intValue() << (8 * (3 - size)))
    } else {
      Bytes.from(value.shiftRight(8 * (size - 3)).intValue())
    }
    ByteString(size.toByte) ++ mantissa.tail
  }

  val onePhPerSecond: Target  = Target.unsafe(BigInteger.ONE.shiftLeft(256 - 50))
  val oneEhPerSecond: Target  = Target.unsafe(BigInteger.ONE.shiftLeft(256 - 60))
  val a128EhPerSecond: Target = Target.unsafe(BigInteger.ONE.shiftLeft(256 - 67))
}
