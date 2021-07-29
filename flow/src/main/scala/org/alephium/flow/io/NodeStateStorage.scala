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

package org.alephium.flow.io

import akka.util.ByteString
import org.rocksdb.{ColumnFamilyHandle, ReadOptions, RocksDB, WriteOptions}

import org.alephium.flow.core.BlockHashChain
import org.alephium.io.{
  IOError,
  IOResult,
  IOUtils,
  RawKeyValueStorage,
  RocksDBColumn,
  RocksDBSource
}
import org.alephium.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.protocol.Hash
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.model.{ChainIndex, Version}
import org.alephium.serde._
import org.alephium.util.AVector

trait NodeStateStorage extends RawKeyValueStorage {

  def config: GroupConfig

  private val isInitializedKey =
    Hash.hash("isInitialized").bytes ++ ByteString(Storages.isInitializedPostfix)

  def isInitialized(): IOResult[Boolean] =
    IOUtils.tryExecute {
      existsRawUnsafe(isInitializedKey)
    }

  def setInitialized(): IOResult[Unit] =
    IOUtils.tryExecute {
      putRawUnsafe(isInitializedKey, ByteString(1))
    }

  private val nodeVersionKey =
    Hash.hash("databaseVersion").bytes ++ ByteString(Storages.nodeVersionPostfix)

  def setNodeVersion(version: Version): IOResult[Unit] =
    IOUtils.tryExecute {
      putRawUnsafe(nodeVersionKey, serialize(version))
    }

  def getNodeVersion: IOResult[Option[Version]] =
    IOUtils.tryExecute {
      getOptRawUnsafe(nodeVersionKey).map(deserialize[Version](_) match {
        case Left(e)  => throw e
        case Right(v) => v
      })
    }

  def checkNodeCompatibility(version: Version): IOResult[Unit] = {
    getNodeVersion.flatMap {
      case Some(nodeVersion) if !version.backwardCompatible(nodeVersion) =>
        Left(
          IOError.Other(
            new RuntimeException(s"Database version is $nodeVersion, client version is $version")
          )
        )
      case Some(nodeVersion) if nodeVersion < version =>
        setNodeVersion(version)
      case None =>
        setNodeVersion(version)
      case _ => Right(())
    }
  }

  private val chainStateKeys = AVector.tabulate(config.groups, config.groups) { (from, to) =>
    ByteString(from.toByte, to.toByte, Storages.chainStatePostfix)
  }

  def chainStateStorage(chainIndex: ChainIndex): ChainStateStorage =
    new ChainStateStorage {
      private val chainStateKey = chainStateKeys(chainIndex.from.value)(chainIndex.to.value)

      override def updateState(state: BlockHashChain.State): IOResult[Unit] =
        IOUtils.tryExecute {
          putRawUnsafe(chainStateKey, serialize(state))
        }

      override def loadState(): IOResult[BlockHashChain.State] =
        IOUtils.tryExecute {
          deserialize[BlockHashChain.State](getRawUnsafe(chainStateKey)) match {
            case Left(e)  => throw e
            case Right(v) => v
          }
        }

      override def clearState(): IOResult[Unit] =
        IOUtils.tryExecute {
          deleteRawUnsafe(chainStateKey)
        }
    }

  def heightIndexStorage(chainIndex: ChainIndex): HeightIndexStorage
}

object NodeStateRockDBStorage {
  def apply(storage: RocksDBSource, cf: ColumnFamily)(implicit
      config: GroupConfig
  ): NodeStateRockDBStorage =
    apply(storage, cf, Settings.writeOptions, Settings.readOptions)

  def apply(storage: RocksDBSource, cf: ColumnFamily, writeOptions: WriteOptions)(implicit
      config: GroupConfig
  ): NodeStateRockDBStorage =
    apply(storage, cf, writeOptions, Settings.readOptions)

  def apply(
      storage: RocksDBSource,
      cf: ColumnFamily,
      writeOptions: WriteOptions,
      readOptions: ReadOptions
  )(implicit config: GroupConfig): NodeStateRockDBStorage = {
    new NodeStateRockDBStorage(storage, cf, writeOptions, readOptions)
  }
}

class NodeStateRockDBStorage(
    val storage: RocksDBSource,
    val cf: ColumnFamily,
    val writeOptions: WriteOptions,
    val readOptions: ReadOptions
)(implicit val config: GroupConfig)
    extends RocksDBColumn
    with NodeStateStorage {
  protected val db: RocksDB                = storage.db
  protected val handle: ColumnFamilyHandle = storage.handle(cf)

  def heightIndexStorage(chainIndex: ChainIndex): HeightIndexStorage =
    new HeightIndexStorage(chainIndex, storage, cf, writeOptions, readOptions)
}
