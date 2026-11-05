package com.rbmr.taigasleep.recording.store

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The server keeps a recording under the phone's id, which has to be unique across accounts. */
class RecordingIdTest {
  private lateinit var root: File
  private lateinit var store: RecordingStore

  // What the server accepts as a recording id: 1 to 40 letters, digits, underscore or dash.
  private val serverId = Regex("[A-Za-z0-9_-]{1,40}")
  private val newId = Regex("""\d{8}_\d{6}-[a-z0-9]{4}""")

  @Before
  fun setUp() {
    root = Files.createTempDirectory("taiga-ids").toFile()
    store = RecordingStore(root)
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  @Test
  fun aNewIdIsTheStartTimeAndAFourCharacterSuffix() {
    val id = store.newRecordingId(1_792_798_200_000)

    assertTrue(id, newId.matches(id))
    assertTrue(id, serverId.matches(id))
  }

  @Test
  fun theSuffixIsDrawnFromLowercaseLettersAndDigits() {
    val random = Random(1)
    repeat(200) {
      val suffix = store.newRecordingId(1_792_798_200_000, random).substringAfter('-')
      assertTrue(suffix, suffix.all { it in 'a'..'z' || it in '0'..'9' })
    }
  }

  @Test
  fun twoIdsInTheSameSecondDiffer() {
    val random = Random(2)
    val ids = (1..50).map { store.newRecordingId(1_792_798_200_000, random) }

    assertEquals(ids.size, ids.toSet().size)
    assertEquals(1, ids.map { it.substringBefore('-') }.toSet().size)
  }

  @Test
  fun anIdWhoseDirectoryExistsIsDrawnAgain() {
    val taken = store.newRecordingId(1_792_798_200_000, Random(3))
    File(root, taken).mkdirs()

    // The same seed would propose the same suffix first.
    val next = store.newRecordingId(1_792_798_200_000, Random(3))

    assertNotEquals(taken, next)
    assertTrue(next, newId.matches(next))
  }

  @Test
  fun anIdFromBeforeTheSuffixIsStillAValidServerIdAndKeepsItsDirectory() {
    // Recordings made by older versions keep their id; it is already within the server's rules.
    val old = "20261023_223000"
    File(root, old).mkdirs()

    assertTrue(serverId.matches(old))
    assertEquals(File(root, old), store.dir(old))
    assertNotEquals(old, store.newRecordingId(1_792_798_200_000))
  }
}
