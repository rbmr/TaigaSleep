package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.storeScope
import com.rbmr.taigasleep.sync.db.testDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val A = "https://sleep.example|u1"
private const val B = "https://sleep.example|u2"
private const val ACCOUNT = A

/** The sync state of one recording, kept in the app's own tables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadStoreTest {
  private lateinit var database: TaigaDatabase
  private lateinit var scope: CoroutineScope
  private val active = MutableStateFlow<String?>(ACCOUNT)

  @Before
  fun setUp() {
    database = testDatabase()
    scope = storeScope()
  }

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
  }

  private fun store(account: StateFlow<String?> = active) =
      UploadStore(database.uploadStates(), account, scope)

  /** The row the database holds for one recording, once the write that changed it returned. */
  private fun UploadStore.row(id: String, account: String? = null): UploadRecord? =
      runBlocking { record(id, account) }

  @Test
  fun stateSurvivesANewStoreInstance() {
    runBlocking { store().markDone("r1") }

    val restarted = store().row("r1")!!

    assertEquals(UploadPhase.DONE, restarted.phase)
    assertTrue(restarted.onServer)
    assertTrue(restarted.uploadedAtMs != null)
  }

  @Test
  fun fullProgressIsNotAConfirmation() {
    val store = store()

    runBlocking { store.markUploading("r1", 1f) }

    val record = store.row("r1")!!
    assertEquals(UploadPhase.UPLOADING, record.phase)
    assertNull(record.uploadedAtMs)
    assertNull(record.uploadConfirmedAt())
  }

  @Test
  fun aConfirmationFromTheServerSurvivesARestart() {
    runBlocking {
      store().apply {
        markUploading("r1", 1f)
        markUploadConfirmed("r1")
      }
    }

    val restarted = store().row("r1")!!

    assertTrue(restarted.confirmedOnServer)
    assertTrue(restarted.uploadConfirmedAt() != null)
  }

  @Test
  fun aFailureAfterTheServerConfirmedTheUploadKeepsTheConfirmation() {
    val store = store()
    runBlocking {
      store.markUploading("r1", 1f)
      store.markUploadConfirmed("r1")

      store.markFailed("r1", UploadFailure.NETWORK, "offline")
    }

    assertTrue(store.row("r1")!!.uploadConfirmedAt() != null)
  }

  @Test
  fun `a recording the server no longer has becomes local only`() {
    val store = store()
    runBlocking {
      store.markDone("r1")

      store.markLocal("r1")
    }

    val record = store.row("r1")!!
    assertEquals(UploadPhase.LOCAL, record.phase)
    assertFalse(record.onServer)
    assertNull(record.uploadedAtMs)
  }

  @Test
  fun clearingOneRecordingLeavesTheOthers() {
    val store = store()
    runBlocking {
      store.markQueued("r1")
      store.markQueued("r2")

      store.clear("r1")
    }

    assertNull(store.row("r1"))
    assertEquals(UploadPhase.QUEUED, store.row("r2")!!.phase)
    assertEquals(setOf("r2"), runBlocking { store.records.first { it.isNotEmpty() } }.keys)
  }

  @Test
  fun `a server copy is only visible for the account it was written under`() {
    val store = store()
    runBlocking { store.markDone("r1") }

    assertEquals(UploadPhase.DONE, store.row("r1")?.phase)

    active.value = B

    // The recording is still on the phone, but not on this account: it is local only here.
    assertNull(store.row("r1"))
    assertNull(store.row("r1", B))

    active.value = A

    assertEquals(UploadPhase.DONE, store.row("r1")?.phase)
    assertTrue(store.row("r1")!!.onServer)
  }

  @Test
  fun `a confirmation made on one account is not counted on another`() {
    val store = store()
    runBlocking { store.markUploadConfirmed("r1") }

    assertTrue(store.row("r1")!!.uploadConfirmedAt() != null)

    active.value = B

    // Raw cleanup must not trim the files the second server never received.
    assertNull(store.row("r1", B)?.uploadConfirmedAt())

    active.value = A

    assertTrue(store.row("r1")!!.uploadConfirmedAt() != null)
  }

  @Test
  fun `another account's copy is reported so an upload can ask first`() {
    val store = store()
    runBlocking { store.markDone("r1") }
    active.value = B

    assertEquals(A, runBlocking { store.otherAccount("r1") })

    active.value = A

    assertNull(runBlocking { store.otherAccount("r1") })
  }

  @Test
  fun `the published rows are the active account's, as the database has them`() = runBlocking {
    val store = store()
    store.markDone("r1")

    assertEquals(setOf("r1"), store.records.first { it.isNotEmpty() }.keys)
    assertNotNull(store.records.first { it["r1"]?.phase == UploadPhase.DONE })

    active.value = B

    // A login moves the published rows with it, without anything asking the store to refresh.
    assertTrue(store.records.first { it.isEmpty() }.isEmpty())
  }
}
