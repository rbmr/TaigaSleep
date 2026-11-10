package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.storeScope
import com.rbmr.taigasleep.sync.db.testDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val A = "https://sleep.example|u1"
private const val B = "https://sleep.example|u2"

/** The remote deletes the app still owes the server, kept in the app's own tables. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingDeleteStoreTest {
  private lateinit var database: TaigaDatabase
  private lateinit var scope: CoroutineScope
  private val accounts = MutableStateFlow<String?>(A)

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

  private fun store() = PendingDeleteStore(database.pendingDeletes(), accounts, scope)

  private fun PendingDeleteStore.ids() = runBlocking { remoteIds() }

  @Test
  fun `a queued delete belongs to the account that queued it`() {
    val store = store()
    runBlocking { store.add("r1") }

    assertEquals(setOf("r1"), store.ids())
    assertEquals(A, runBlocking { store.pending.first { it.isNotEmpty() } }.getValue("r1").account)

    accounts.value = B

    // The delete of A's copy is not B's, so B neither sees nor runs it.
    assertTrue(store.ids().isEmpty())
    assertTrue(runBlocking { store.pending.first { it.isEmpty() } }.isEmpty())

    accounts.value = A

    assertEquals(setOf("r1"), store.ids())
  }

  @Test
  fun `removing one account's delete leaves the other's`() {
    val store = store()
    runBlocking {
      store.add("r1")
      accounts.value = B
      store.add("r1")

      store.remove("r1")
    }

    assertTrue(store.ids().isEmpty())
    accounts.value = A
    assertEquals(setOf("r1"), store.ids())
  }

  @Test
  fun `a delete without an account is not queued`() {
    accounts.value = null
    val store = store()

    runBlocking { store.add("r1") }

    assertTrue(store.ids().isEmpty())
    assertTrue(store.pending.value.isEmpty())
  }

  @Test
  fun `the queue survives a new store instance`() {
    runBlocking { store().add("r1") }

    assertEquals(setOf("r1"), store().ids())
  }
}
