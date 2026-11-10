package com.rbmr.taigasleep.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "https://sleep.example|u1"
private const val B = "https://sleep.example|u2"

private class MemoryDeletes : PendingDeleteStorage {
  var document: String? = null

  override fun read(): String? = document

  override fun write(json: String) {
    document = json
  }
}

class PendingDeleteStoreTest {
  private val storage = MemoryDeletes()
  private var active: String? = A

  private fun store() = PendingDeleteStore(storage) { active }

  @Test
  fun `a queued delete belongs to the account that queued it`() {
    val store = store()
    store.add("r1")

    assertEquals(setOf("r1"), store.remoteIds())
    assertEquals(A, store.pending.value.getValue("r1").account)

    active = B
    store.refreshAccount()

    // The delete of A's copy is not B's, so B neither sees nor runs it.
    assertTrue(store.remoteIds().isEmpty())
    assertTrue(store.pending.value.isEmpty())

    active = A
    store.refreshAccount()

    assertEquals(setOf("r1"), store.remoteIds())
  }

  @Test
  fun `a queue written before deletes carried an account belongs to the account logged in`() {
    storage.document = """{"r1":{"remoteId":"r1","requestedAtMs":10}}"""
    active = null
    val store = store()

    // There is no account to attribute it to yet, so it is not sent anywhere.
    assertTrue(store.remoteIds().isEmpty())

    active = A
    store.refreshAccount()

    assertEquals(setOf("r1"), store.remoteIds())
    assertEquals(A, store.pending.value.getValue("r1").account)
  }

  @Test
  fun `removing one account's delete leaves the other's`() {
    active = A
    var store = store()
    store.add("r1")
    active = B
    store = PendingDeleteStore(storage) { active }
    store.add("r1")

    store.remove("r1")

    assertTrue(store.remoteIds().isEmpty())
    active = A
    store.refreshAccount()
    assertEquals(setOf("r1"), store.remoteIds())
  }

  @Test
  fun `a delete without an account is not queued`() {
    active = null
    val store = store()

    store.add("r1")

    assertTrue(store.remoteIds().isEmpty())
    assertNull(storage.document)
  }
}
