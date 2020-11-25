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
import org.rocksdb.{ReadOptions, WriteOptions}

import org.alephium.io._
import org.alephium.io.RocksDBSource.{ColumnFamily, Settings}
import org.alephium.protocol.Hash
import org.alephium.protocol.vm.WorldState

trait WorldStateStorage extends KeyValueStorage[Hash, WorldState.Hashes] {
  val trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node]

  override def storageKey(key: Hash): ByteString = key.bytes ++ ByteString(Storages.trieHashPostfix)

  def getPersistedWorldState(hash: Hash): IOResult[WorldState.Persisted] = {
    get(hash).map(_.toPersistedWorldState(trieStorage))
  }

  def getCachedWorldState(hash: Hash): IOResult[WorldState.Cached] = {
    get(hash).map(_.toCachedWorldState(trieStorage))
  }

  def putTrie(hash: Hash, worldState: WorldState): IOResult[Unit] = {
    worldState.persist.flatMap(state => put(hash, state.toHashes))
  }
}

object WorldStateRockDBStorage {
  def apply(trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions): WorldStateRockDBStorage = {
    new WorldStateRockDBStorage(trieStorage, storage, cf, writeOptions, Settings.readOptions)
  }

  def apply(trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
            storage: RocksDBSource,
            cf: ColumnFamily,
            writeOptions: WriteOptions,
            readOptions: ReadOptions): WorldStateRockDBStorage = {
    new WorldStateRockDBStorage(trieStorage, storage, cf, writeOptions, readOptions)
  }
}

class WorldStateRockDBStorage(
    val trieStorage: KeyValueStorage[Hash, SparseMerkleTrie.Node],
    storage: RocksDBSource,
    cf: ColumnFamily,
    writeOptions: WriteOptions,
    readOptions: ReadOptions
) extends RocksDBKeyValueStorage[Hash, WorldState.Hashes](storage, cf, writeOptions, readOptions)
    with WorldStateStorage
