package org.alephium.crypto

import java.nio.charset.Charset

import scala.reflect.runtime.universe.TypeTag

import akka.util.ByteString
import org.bouncycastle.crypto.Digest

import org.alephium.serde._

object HashSchema {
  def unsafeBlake2b(bs: ByteString): Blake2b = {
    assume(bs.size == Blake2b.length)
    new Blake2b(bs)
  }

  def unsafeKeccak256(bs: ByteString): Keccak256 = {
    assume(bs.size == Keccak256.length)
    new Keccak256(bs)
  }

  def unsafeSha256(bs: ByteString): Sha256 = {
    assume(bs.size == Sha256.length)
    new Sha256(bs)
  }

  def unsafeByte32(bs: ByteString): Byte32 = {
    assume(bs.size == Byte32.length)
    new Byte32(bs)
  }
}

abstract class HashSchema[T: TypeTag](unsafe: ByteString => T, toBytes: T => ByteString)
    extends RandomBytes.Companion[T](unsafe, toBytes) {
  def provider: Digest

  def hash(input: Seq[Byte]): T = {
    val _provider = provider
    _provider.update(input.toArray, 0, input.length)
    val res = new Array[Byte](length)
    _provider.doFinal(res, 0)
    unsafe(ByteString.fromArrayUnsafe(res))
  }

  def hash(input: String): T = {
    hash(ByteString.fromString(input))
  }

  def hash(input: String, charset: Charset): T = {
    hash(ByteString.fromString(input, charset))
  }

  def hash[S](input: S)(implicit serializer: Serializer[S]): T = {
    hash(serializer.serialize(input))
  }

  def random: T = generate
}
