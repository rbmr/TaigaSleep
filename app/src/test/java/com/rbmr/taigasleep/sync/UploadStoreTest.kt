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
    UploadStore(storage).markDone("r1")

    val restarted = UploadStore(storage).record("r1")!!

    assertEquals(UploadPhase.DONE, restarted.phase)
    assertTrue(restarted.onServer)
    assertTrue(restarted.uploadedAtMs != null)
  }

  @Test
  fun aSyncFileWithAnalysisFactsStillLoads() {
    // What an older app wrote: the sync state beside a copy of the analysis facts. The unknown keys
    // are ignored, and the next write drops them.
    val storage = MemoryStorage()
    storage.documents["r1"] =
        """
        {"id":"r1","onServer":true,"analysisId":"a1","phase":"DONE","progress":1.0,
         "epochSeconds":30,"segments":[{"stage":"light","count":4}],"sleepScore":81.0,
         "model":"baseline","modelVersion":"1.0.0","resultDownloaded":true,
         "updatedAtMs":10,"uploadedAtMs":9,"keepRawOnPhone":true}
        """
    val store = UploadStore(storage)

    val record = store.record("r1")!!

    assertEquals(UploadPhase.DONE, record.phase)
    assertTrue(record.onServer)
    assertTrue(record.keepRawOnPhone)
    assertEquals(9L, record.uploadedAtMs)

    store.markQueued("r1")

    assertFalse("analysisId" in storage.documents.getValue("r1"))
    assertFalse("sleepScore" in storage.documents.getValue("r1"))
  }

  @Test
  fun clearingOneRecordingLeavesTheOthers() {    val storage = MemoryStorage()
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
