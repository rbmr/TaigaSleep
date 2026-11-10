package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.recording.store.RecordingStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "https://sleep.example|u1"
private const val B = "https://sleep.example|u2"
private const val ACCOUNT = A

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
    UploadStore(storage, account = { ACCOUNT }).markDone("r1")

    val restarted = UploadStore(storage, account = { ACCOUNT }).record("r1")!!

    assertEquals(UploadPhase.DONE, restarted.phase)
    assertTrue(restarted.onServer)
    assertTrue(restarted.uploadedAtMs != null)
  }

  @Test
  fun fullProgressIsNotAConfirmation() {
    val store = UploadStore(MemoryStorage())

    store.markUploading("r1", 1f)

    val record = store.record("r1")!!
    assertEquals(UploadPhase.UPLOADING, record.phase)
    assertNull(record.uploadedAtMs)
    assertNull(record.uploadConfirmedAt())
  }

  @Test
  fun aConfirmationFromTheServerSurvivesARestart() {
    val storage = MemoryStorage()
    val store = UploadStore(storage)
    store.markUploading("r1", 1f)
    store.markUploadConfirmed("r1")

    val restarted = UploadStore(storage).record("r1")!!

    assertTrue(restarted.confirmedOnServer)
    assertTrue(restarted.uploadConfirmedAt() != null)
  }

  @Test
  fun aFailureAfterTheServerConfirmedTheUploadKeepsTheConfirmation() {
    val store = UploadStore(MemoryStorage())
    store.markUploading("r1", 1f)
    store.markUploadConfirmed("r1")

    store.markFailed("r1", UploadFailure.NETWORK, "offline")

    assertTrue(store.record("r1")!!.uploadConfirmedAt() != null)
  }

  @Test
  fun `a recording the server no longer has becomes local only`() {
    val storage = MemoryStorage()
    val store = UploadStore(storage)
    store.markDone("r1")

    store.markLocal("r1")

    val record = store.record("r1")!!
    assertEquals(UploadPhase.LOCAL, record.phase)
    assertFalse(record.onServer)
    assertNull(record.uploadedAtMs)
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
    val store = UploadStore(storage, account = { ACCOUNT })

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

    val store = UploadStore(storage, account = { ACCOUNT })

    assertEquals(UploadPhase.QUEUED, store.record("r1")!!.phase)
    assertNull(storage.legacyDocument)
  }

  @Test
  fun `a server copy is only visible for the account it was written under`() {
    val storage = MemoryStorage()
    var active: String? = A
    val store = UploadStore(storage) { active }
    store.markDone("r1")

    assertEquals(UploadPhase.DONE, store.record("r1")?.phase)

    active = B
    store.refreshAccount()

    // The recording is still on the phone, but not on this account: it is local only here.
    assertNull(store.record("r1"))
    assertFalse("r1" in store.records.value)

    active = A
    store.refreshAccount()

    assertEquals(UploadPhase.DONE, store.record("r1")?.phase)
    assertTrue(store.record("r1")!!.onServer)
  }

  @Test
  fun `a confirmation made on one account is not counted on another`() {
    val storage = MemoryStorage()
    var active: String? = A
    val store = UploadStore(storage) { active }
    store.markUploadConfirmed("r1")

    assertTrue(store.record("r1")!!.uploadConfirmedAt() != null)

    active = B
    store.refreshAccount()

    // Raw cleanup must not trim the files the second server never received.
    assertNull(store.record("r1")?.uploadConfirmedAt())

    active = A
    store.refreshAccount()

    assertTrue(store.record("r1")!!.uploadConfirmedAt() != null)
  }

  @Test
  fun `another account's copy is reported so an upload can ask first`() {
    val storage = MemoryStorage()
    var active: String? = A
    val store = UploadStore(storage) { active }
    store.markDone("r1")
    active = B
    store.refreshAccount()

    assertEquals(A, store.otherAccount("r1"))

    active = A
    store.refreshAccount()

    assertNull(store.otherAccount("r1"))
  }

  @Test
  fun `a state written before accounts existed is adopted by the account logged in`() {
    val storage = MemoryStorage()
    storage.documents["r1"] = """{"id":"r1","onServer":true,"phase":"DONE","updatedAtMs":10}"""
    var active: String? = null
    val store = UploadStore(storage) { active }

    // Nobody is logged in, so there is no account the state can belong to.
    assertNull(store.record("r1"))

    active = A
    store.refreshAccount()

    assertEquals(UploadPhase.DONE, store.record("r1")!!.phase)
    assertTrue(store.record("r1")!!.onServer)
    assertTrue(storage.documents.getValue("r1").contains(A))
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
