package com.unitrading.app

import com.unitrading.tradovate.CandleCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reuse the platform's atomic snapshot storage, outside the UI and socket event threads. */
internal class SnapshotCandleCacheStore(private val store: SnapshotStore) : CandleCacheStore {
    override suspend fun read(key: String): String? = withContext(Dispatchers.Default) { store.read(key) }
    override suspend fun write(key: String, value: String) = withContext(Dispatchers.Default) { store.write(key, value) }
}
