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

package org.alephium.appserver

import io.circe
import io.circe.syntax._
import sttp.tapir.{Codec, DecodeResult, Validator}
import sttp.tapir.CodecFormat.TextPlain

import org.alephium.appserver.ApiModel._
import org.alephium.protocol.{Hash, PublicKey}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{Address, GroupIndex}
import org.alephium.util.{TimeStamp, U256}

trait TapirCodecs extends ApiModelCodec {
  implicit val timestampTapirCodec: Codec[String, TimeStamp, TextPlain] =
    Codec.long.validate(Validator.min(0L)).map(TimeStamp.unsafe(_))(_.millis)

  implicit val hashTapirCodec: Codec[String, Hash, TextPlain] =
    fromCirce[Hash]

  implicit val addressTapirCodec: Codec[String, Address, TextPlain] =
    fromCirce[Address]

  implicit val apiKeyTapirCodec: Codec[String, ApiKey, TextPlain] =
    fromCirce[ApiKey]

  implicit val publicKeyTapirCodec: Codec[String, PublicKey, TextPlain] =
    fromCirce[PublicKey]

  implicit val u256TapirCodec: Codec[String, U256, TextPlain] =
    fromCirce[U256]

  implicit val minerActionTapirCodec: Codec[String, MinerAction, TextPlain] =
    fromCirce[MinerAction]

  implicit def groupIndexCodec(
      implicit groupConfig: GroupConfig): Codec[String, GroupIndex, TextPlain] =
    Codec.int.mapDecode(int =>
      GroupIndex.from(int) match {
        case Some(groupIndex) => DecodeResult.Value(groupIndex)
        case None =>
          DecodeResult.Error(s"$int", new IllegalArgumentException("Invalid group index"))
    })(_.value)

  private def fromCirce[A: circe.Codec]: Codec[String, A, TextPlain] =
    Codec.string.mapDecode[A] { raw =>
      raw.asJson.as[A] match {
        case Right(a) => DecodeResult.Value(a)
        case Left(error) =>
          DecodeResult.Error(raw, new IllegalArgumentException(error.getMessage))
      }
    }(_.asJson.toString)
}
