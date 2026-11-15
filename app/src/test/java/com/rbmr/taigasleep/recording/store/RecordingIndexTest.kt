package com.rbmr.taigasleep.recording.store

import com.rbmr.taigasleep.tracker.RecordingMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The queries of a reconcile read one index: a directory scan that reads each meta.json once and is
 * repeated only after a recording changed. A year of nights is hundreds of directories, so the
 * number of reads is the point.
 */
class RecordingIndexTest {
  @get:Rule val tmp = TemporaryFolder()

  /** How many times the store read a meta.json, per recording directory. */
  private val reads = mutableMapOf<File, Int>()

  /** Ids are drawn from one counter, so two seed calls never write the same directory. */
  private var seeded = 0

  private fun store(root: File) =
      RecordingStore(root) { dir ->
        reads.merge(dir, 1, Int::plus)
        RecordingMeta.read(dir)
      }

  /** Writes [count] finished nights, each one starting a day after the one before. */
  private fun seed(
      root: File,
      count: Int,
      startUtcMs: Long = 1_760_000_000_000,
      state: RecordingPhase = RecordingPhase.STOPPED,
      mode: RecordingMode = RecordingMode.STREAM,
      fetchPending: Boolean = false,
      trackerRemovalPending: List<String> = emptyList(),
  ): List<String> =
      (0 until count).map { i ->
        val number = seeded++
        val id = "202610%02d_223000-%04d".format(number % 28 + 1, number)
        val dir = File(root, id).apply { mkdirs() }
        meta(
                id = id,
                startUtcMs = startUtcMs + i * 86_400_000L,
                state = state,
                mode = mode,
                fetchPending = fetchPending,
                trackerRemovalPending = trackerRemovalPending,
            )
            .write(dir)
        id
      }

  private fun meta(
      id: String,
      startUtcMs: Long,
      state: RecordingPhase,
      mode: RecordingMode,
      fetchPending: Boolean,
      trackerRemovalPending: List<String>,
  ) =
      RecordingMeta(
          recordingId = id,
          state = state,
          mode = mode,
          startUtcMs = startUtcMs,
          endUtcMs = startUtcMs + 3_600_000L,
          timezone = "Europe/Amsterdam",
          appVersion = "0.7.51",
          appVersionCode = 7051,
          phoneManufacturer = "Test",
          phoneModel = "T1",
          android = "16",
          sdk = 36,
          sensorKind = "polar-h10",
          sensorId = "SEED",
          sensorName = "Polar H10 SEED",
          sensorFirmware = null,
          sensorBatteryAtStart = null,
          config = RecordingConfig(ecg = false, acc = false),
          fetchPending = fetchPending,
          trackerRemovalPending = trackerRemovalPending,
      )

  @Test
  fun `one scan reads each meta json once for all five queries`() {
    val root = tmp.newFolder("recordings")
    seed(root, 300)
    // One night the tracker still holds, so the fetch query has an answer to find.
    seed(
        root,
        count = 1,
        startUtcMs = 1_770_000_000_000,
        state = RecordingPhase.FETCH_PENDING,
        mode = RecordingMode.ON_DEVICE,
        fetchPending = true,
        trackerRemovalPending = listOf("entry-1"),
    )
    val recordingStore = store(root)

    val listed = recordingStore.list()
    recordingStore.markOrphans(liveId = null)
    recordingStore.onDeviceInProgress()
    recordingStore.fetchPending()
    recordingStore.pendingTrackerRemovals()

    assertEquals(301, listed.size)
    assertEquals("every meta.json is read once per scan", 301, reads.size)
    assertEquals(setOf(1), reads.values.toSet())
  }

  @Test
  fun `the queries after one scan read no meta json again`() {
    val root = tmp.newFolder("recordings")
    seed(root, 50)
    val recordingStore = store(root)
    recordingStore.list()

    recordingStore.list()
    recordingStore.markOrphans(liveId = null)
    recordingStore.onDeviceInProgress()
    recordingStore.fetchPending()
    recordingStore.pendingTrackerRemovals()

    assertEquals(setOf(1), reads.values.toSet())
  }

  @Test
  fun `a recording that changed is scanned again and the rest are read once more`() {
    val root = tmp.newFolder("recordings")
    val ids = seed(root, 20, state = RecordingPhase.RECORDING)
    val recordingStore = store(root)
    assertEquals(RecordingPhase.RECORDING, recordingStore.list().first().state)

    // The process died: the reconcile closes every recorded night.
    assertEquals(ids.sortedDescending(), recordingStore.markOrphans(liveId = null).sortedDescending())

    assertEquals(RecordingPhase.INTERRUPTED, recordingStore.list().first().state)
    assertEquals(setOf(2), reads.values.toSet())
  }

  @Test
  fun `a recording that ended is listed with its new state`() {
    val root = tmp.newFolder("recordings")
    val id = seed(root, 1, state = RecordingPhase.RECORDING).single()
    val recordingStore = store(root)
    assertEquals(RecordingPhase.RECORDING, recordingStore.list().single().state)

    val dir = File(root, id)
    RecordingMeta.read(dir)!!.copy(state = RecordingPhase.STOPPED).write(dir)

    assertEquals(RecordingPhase.STOPPED, recordingStore.list().single().state)
  }

  @Test
  fun `a deleted recording leaves the index`() {
    val root = tmp.newFolder("recordings")
    val ids = seed(root, 2)
    val recordingStore = store(root)
    assertEquals(2, recordingStore.list().size)

    assertEquals(true, recordingStore.delete(ids.first()))

    assertEquals(1, recordingStore.list().size)
    assertNull(recordingStore.list().firstOrNull { it.id == ids.first() })
  }

  @Test
  fun `the queries find what a reconcile looks for`() {
    val root = tmp.newFolder("recordings")
    seed(root, 5)
    val onDevice =
        seed(
                root,
                count = 1,
                startUtcMs = 1_770_000_000_000,
                state = RecordingPhase.RECORDING,
                mode = RecordingMode.ON_DEVICE,
            )
            .single()
    val pending =
        seed(
                root,
                count = 1,
                startUtcMs = 1_780_000_000_000,
                state = RecordingPhase.FETCH_PENDING,
                mode = RecordingMode.ON_DEVICE,
                fetchPending = true,
            )
            .single()
    val removal =
        seed(
                root,
                count = 1,
                startUtcMs = 1_790_000_000_000,
                mode = RecordingMode.ON_DEVICE,
                trackerRemovalPending = listOf("entry-2"),
            )
            .single()
    val recordingStore = store(root)

    assertEquals(onDevice, recordingStore.onDeviceInProgress()?.recordingId)
    assertEquals(pending, recordingStore.fetchPending()?.recordingId)
    assertEquals(removal, recordingStore.pendingTrackerRemovals().single().recordingId)
    assertEquals(setOf(1), reads.values.toSet())
  }
}
