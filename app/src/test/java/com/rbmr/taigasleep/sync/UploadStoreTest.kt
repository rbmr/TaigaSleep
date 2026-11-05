package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.recording.store.RecordingStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryStorage : UploadRecordsStorage {
  val documents = mutableMapOf<String, String>()
  var legacyDocument: String? = null

  override fun ids(): Set<String> = documents.keys

  override fun read(id: String): String? = documents[id]

  override fun write(id: String, json: String) {
    documents[id] = json
  }

  override fun delete(id: String) {
    documents.remove(id)
  }

  override fun legacy(): String? = legacyDocument

  override fun clearLegacy() {
    legacyDocument = null
  }
}

class UploadStoreTest {
  @Test
  fun stateSurvivesANewStoreInstance() {
    val storage = MemoryStorage()
    UploadStore(storage).markDone(
        id = "r1",
        analysisId = "a1",
        model = "baseline",
        modelVersion = "1.0.0",
        epochSeconds = 30,
        segments = emptyList(),
        sleepScore = 80.0,
        resultDownloaded = true,
    )

    val restarted = UploadStore(storage).record("r1")!!

    assertEquals(UploadPhase.DONE, restarted.phase)
    assertEquals("a1", restarted.analysisId)
    assertEquals(80.0, restarted.sleepScore ?: 0.0, 0.001)
    assertTrue(restarted.resultDownloaded)
  }

  @Test
  fun clearingOneRecordingLeavesTheOthers() {
    val storage = MemoryStorage()
    val store = UploadStore(storage)
    store.markQueued("r1")
    store.markQueued("r2")

    store.clear("r1")

    assertNull(store.record("r1"))
    assertEquals(UploadPhase.QUEUED, store.record("r2")!!.phase)
    assertFalse("r1" in storage.documents)
  }

  @Test
  fun theOldSharedDocumentIsMigratedOnce() {
    val storage = MemoryStorage()
    storage.legacyDocument =
        """{"r1":{"id":"r1","phase":"QUEUED","updatedAtMs":10},"remote-x":{"id":"remote-x","phase":"DONE","updatedAtMs":10}}"""
    // Only a recording whose directory exists can be migrated.
    storage.documents["r1"] = ""

    val store = UploadStore(storage)

    assertEquals(UploadPhase.QUEUED, store.record("r1")!!.phase)
    assertNull(storage.legacyDocument)
  }

  @Test
  fun directoryStorageKeepsTheStateBesideTheRecording() {
    val root = Files.createTempDirectory("taiga-sync").toFile()
    val recording = java.io.File(root, "r1").apply { mkdirs() }
    val storage = RecordingDirectoryStorage(root)

    storage.write("r1", """{"id":"r1","phase":"UPLOADING"}""")
    storage.write("remote-x", """{"id":"remote-x","phase":"DONE"}""")

    assertTrue(java.io.File(recording, RecordingStore.SYNC_FILE).isFile)
    assertEquals(setOf("r1"), storage.ids())
    assertEquals("""{"id":"r1","phase":"UPLOADING"}""", storage.read("r1"))
    assertNull(storage.read("remote-x"))

    storage.delete("r1")
    assertFalse(java.io.File(recording, RecordingStore.SYNC_FILE).exists())
    root.deleteRecursively()
  }
}
