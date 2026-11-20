package com.andharness.ru.data.index

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide lock shared by the whole-device indexers ([DeviceImageIndexer] and
 * [DeviceDocumentIndexer]) so an image pass and a document pass never run at the same time. Both
 * passes decode/extract files and drive the same on-device embedder process, which is heavy; running
 * them serialised keeps peak memory and model contention bounded while the DB writes stay idempotent.
 *
 * A single [Mutex] lives for the process lifetime (a Hilt `@Singleton`), matching the per-process
 * serialisation the image indexer used before the document indexer was added.
 */
@Singleton
class DeviceIndexLock @Inject constructor() {
    val mutex = Mutex()
}
