package com.rbmr.taigasleep.sync.db

import android.content.Context
import android.content.SharedPreferences
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.sync.PendingDelete
import com.rbmr.taigasleep.sync.UploadFailure
import com.rbmr.taigasleep.sync.UploadPhase
import com.rbmr.taigasleep.sync.UploadRecord
import com.rbmr.taigasleep.sync.UploadStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val A = "https://sleep.example|u1"
private const val B = "https://sleep.example|u2"
/** The address-and-email key older versions saved one account's list under. */
private const val OLD_KEY = "https://sleep.example|me@example.com"

/** A recording on the phone, listed by A with one run and holding one downloaded payload. */
private const val R1 = "20261023_223000-k3f9"
/** A recording on the phone whose payload is keyed per analysis as well as per recording. */
private const val R2 = "20261024_223000-m1a2"
/** A recording whose sync state was only ever in the old shared upload document. */
private const val R3 = "20261025_223000-q7w8"
/** A recording only the server has, listed by A, with a payload A's cache holds for it. */
private const val R4 = "20261027_223000-t9u0"
/** A recording only the server has, listed by B, with a payload B's cache holds for it. */
private const val R5 = "20261028_223000-z2x3"

/**
 * The one-time move off the files. Every comparison below reads the same fixture through the store
 * class that wrote it, so a row that round-trips differently from what the store returns shows up
 * as a difference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyFilesMigrationTest {
  private lateinit var context: Context
  private lateinit var root: File
  private lateinit var database: TaigaDatabase
  private lateinit var fixture: Fixture
  private lateinit var scope: CoroutineScope

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    root = Files.createTempDirectory("taiga-legacy").toFile()
    database = testDatabase()
    scope = storeScope()
    fixture = Fixture(root, context)
  }

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
    root.deleteRecursively()
  }

  @Test
  fun `every file's state moves into the database`() {
    fixture.writeCurrentFiles()

    assertTrue(runBlocking { fixture.migration(A).migrate(database) })

    assertUploadStates(
        mapOf(
            A to
                mapOf(
                    R1 to
                        UploadRecord(
                            id = R1,
                            onServer = true,
                            phase = UploadPhase.DONE,
                            progress = 1f,
                            updatedAtMs = 1000,
                            uploadedAtMs = 900,
                            confirmedOnServer = true,
                            manualAnalysisIds = listOf("a1"),
                        ),
                    R2 to
                        UploadRecord(
                            id = R2,
                            onServer = true,
                            phase = UploadPhase.PROCESSING,
                            progress = 1f,
                            updatedAtMs = 1200,
                            uploadedAtMs = 1150,
                            keepRawOnPhone = true,
                        ),
                    R3 to
                        UploadRecord(
                            id = R3,
                            phase = UploadPhase.FAILED,
                            failure = UploadFailure.NETWORK,
                            updatedAtMs = 1300,
                        ),
                ),
            B to
                mapOf(
                    R1 to UploadRecord(id = R1, phase = UploadPhase.QUEUED, updatedAtMs = 1100),
                ),
        )
    )
    assertRemoteHistory(
        mapOf(
            A to
                (
                    listOf(
                        fixture.recordingDto(
                            R1,
                            complete = true,
                            runs = listOf(fixture.runDto("a1", R1, "baseline", "succeeded")),
                        ),
                        fixture.recordingDto(R4, complete = false),
                    ) to mapOf("remote-$R4" to "a9")
                ),
            B to
                (
                    listOf(
                        fixture.recordingDto(
                            R5,
                            complete = true,
                            runs = listOf(fixture.runDto("a5", R5, "stager", "running")),
                        )
                    ) to emptyMap()
                ),
        )
    )
    assertAnalysisResults(
        mapOf(
            Triple(A, R1, "a1") to fixture.payloadDto("a1", R1, "baseline", "1.0.0"),
            Triple(A, R2, "a2") to fixture.payloadDto("a2", R2, "baseline", "1.0.0"),
            Triple(A, "remote-$R4", "a9") to fixture.payloadDto("a9", R4, "stager", "2.0.0"),
            Triple(B, "remote-$R5", "a5") to fixture.payloadDto("a5", R5, "stager", "2.0.0"),
        )
    )
    assertPendingDeletes(
        mapOf(
            A to PendingDelete(remoteId = "rf-1", account = A, requestedAtMs = 1500),
            B to PendingDelete(remoteId = "rf-2", account = B, requestedAtMs = 1600),
        )
    )
  }

  @Test
  fun `the formats an older version wrote move too`() {
    fixture.writeLegacyFiles()

    assertTrue(runBlocking { fixture.migration(A, legacy = OLD_KEY).migrate(database) })

    assertUploadStates(
        mapOf(
            A to
                mapOf(
                    // The fields an older version wrote beside the sync state are ignored, and the
                    // facts History shows come from the results, not from the upload state.
                    R1 to
                        UploadRecord(
                            id = R1,
                            onServer = true,
                            phase = UploadPhase.DONE,
                            progress = 1f,
                            updatedAtMs = 1000,
                            uploadedAtMs = 900,
                            keepRawOnPhone = true,
                        ),
                    R2 to UploadRecord(id = R2, phase = UploadPhase.QUEUED, updatedAtMs = 1200),
                    R3 to
                        UploadRecord(
                            id = R3,
                            phase = UploadPhase.FAILED,
                            failure = UploadFailure.NETWORK,
                            errorDetail = "offline",
                            updatedAtMs = 1300,
                        ),
                ),
        )
    )
    // The list saved under the address and the email belongs to the account key the app uses now.
    assertRemoteHistory(
        mapOf(
            A to
                (
                    listOf(
                        fixture.recordingDto(
                            R1,
                            complete = true,
                            runs = listOf(fixture.runDto("a1", R1, "baseline", "succeeded")),
                        )
                    ) to mapOf(R1 to "a1")
                )
        )
    )
    assertAnalysisResults(mapOf(Triple(A, R2, "a2") to fixture.flatPayloadDto(R2)))
    assertPendingDeletes(
        mapOf(A to PendingDelete(remoteId = "rf-3", account = A, requestedAtMs = 1700))
    )
  }

  @Test
  fun `the migration runs once`() {
    fixture.writeCurrentFiles()
    val migration = fixture.migration(A)

    assertTrue(runBlocking { migration.migrate(database) })
    val rows = runBlocking { database.uploadStates().rows(A) }
    // A file that changed after the migration only reaches the database if the migration runs again.
    File(fixture.recordings, "$R2/sync.json")
        .writeText(
            """{"accounts":{"$A":{"id":"$R2","phase":"FAILED","failure":"SERVER","updatedAtMs":9}}}"""
        )

    assertFalse(runBlocking { migration.migrate(database) })
    assertEquals(rows, runBlocking { database.uploadStates().rows(A) })
  }

  @Test
  fun `without a session nothing is written, nothing is deleted and the marker stays empty`() {
    fixture.writeCurrentFiles()

    assertFalse(runBlocking { fixture.migration(null).migrate(database) })

    assertEquals(0, runBlocking { database.migrations().runs("files-v1") })
    assertTrue(runBlocking { database.uploadStates().rows(A) }.isEmpty())
    assertTrue(runBlocking { database.remoteRecordings().rows(A) }.isEmpty())
    assertTrue(runBlocking { database.remoteRecordings().analysisRows(A) }.isEmpty())
    assertTrue(runBlocking { database.analysisResults().rows(A) }.isEmpty())
    assertTrue(runBlocking { database.pendingDeletes().rows(A) }.isEmpty())
    // The state cannot be attributed yet, so its files stay until an account is there.
    assertTrue(File(fixture.recordings, "$R1/sync.json").isFile)
    assertTrue(fixture.historyFile.isFile)
    assertTrue(fixture.analysisRoot.isDirectory)
  }

  @Test
  fun `the files are gone once their content is in the database`() {
    fixture.writeCurrentFiles()
    // A temporary document an older version wrote the sync state through.
    File(fixture.recordings, "$R1/sync.json.tmp").writeText("{}")

    assertTrue(runBlocking { fixture.migration(A).migrate(database) })

    for (id in listOf(R1, R2, R3)) {
      assertFalse(File(fixture.recordings, "$id/sync.json").exists())
      assertTrue("the recording itself stays", File(fixture.recordings, id).isDirectory)
    }
    assertFalse(File(fixture.recordings, "$R1/sync.json.tmp").exists())
    assertNull(fixture.uploadPreferences.getString("records", null))
    assertFalse(fixture.historyFile.exists())
    assertFalse(fixture.analysisRoot.exists())
    assertNull(fixture.deletePreferences.getString("pending_remote_deletes", null))
  }

  @Test
  fun `the cleanup tolerates files that are not there`() {
    // No older version ever wrote anything, so there is nothing to import and nothing to delete.
    runBlocking { fixture.migration(A).awaitMigrated(database) }

    assertEquals(1, runBlocking { database.migrations().runs("files-v1") })
    assertTrue(runBlocking { database.uploadStates().rows(A) }.isEmpty())

    // A second start deletes what it does not find and does not import again.
    runBlocking { fixture.migration(A).awaitMigrated(database) }
    assertEquals(1, runBlocking { database.migrations().runs("files-v1") })
  }

  @Test
  fun `a start that only has the cleanup left finishes it without importing again`() {
    fixture.writeCurrentFiles()
    assertTrue(runBlocking { fixture.migration(A).migrate(database) })
    val rows = runBlocking { database.uploadStates().rows(A) }
    // The process died before the deletion, so the files are still there on the next start.
    fixture.writeCurrentFiles()

    runBlocking { fixture.migration(A).awaitMigrated(database) }

    assertFalse(File(fixture.recordings, "$R1/sync.json").exists())
    assertFalse(fixture.historyFile.exists())
    assertFalse(fixture.analysisRoot.exists())
    assertEquals(
        "nothing was imported a second time",
        rows,
        runBlocking { database.uploadStates().rows(A) },
    )
  }

  @Test
  fun `payloads of an account nothing names are discarded instead of attributed`() {
    fixture.writeCurrentFiles()
    // The results of an account that is not logged in and that no file on the phone mentions.
    val stranger =
        File(fixture.analysisRoot, resultCacheDirectoryName("https://other.example|u9")).apply {
          mkdirs()
        }
    File(stranger, "$R1.json").writeJson(fixture.strangerPayloadJson())

    assertTrue(runBlocking { fixture.migration(A).migrate(database) })

    // Its payload is not given to the account that is logged in, and the directory does not stay.
    assertEquals(
        "baseline",
        runBlocking { database.analysisResults().row(A, R1, "a1") }?.toAnalysisDto()?.algorithm,
    )
    assertFalse(stranger.exists())
  }

  @Test
  fun `a store waits for the import before it reads`() = runBlocking {
    fixture.writeCurrentFiles()
    val accounts = MutableStateFlow<String?>(null)
    val migration = fixture.migration(account = { accounts.value })
    val store =
        UploadStore(database.uploadStates(), accounts, scope) {
          migration.awaitMigrated(database)
        }

    // There is no account yet, so nothing can be attributed and no row is shown.
    assertNull(store.record(R1))
    assertEquals(0, database.migrations().runs("files-v1"))

    accounts.value = A

    // The same read now imports the files first, so it sees what they held.
    assertEquals(UploadPhase.DONE, store.record(R1)?.phase)
    assertEquals(1, database.migrations().runs("files-v1"))
  }

  @Test
  fun `the sync files are found where the recordings are`() {
    fixture.writeCurrentFiles()
    val files = LegacyUploadFiles(fixture.recordings, fixture.uploadPreferences)

    assertEquals(setOf(R1, R2, R3), files.ids())
    assertTrue(files.hasRecording(R1))
    assertFalse(files.hasRecording(R4))
    assertNull(files.read(R4))
  }

  @Test
  fun `each account reads its own rows from the flow`() {
    fixture.writeCurrentFiles()
    runBlocking { fixture.migration(A).migrate(database) }

    val forA = runBlocking { database.uploadStates().observe(A).first() }
    val forB = runBlocking { database.uploadStates().observe(B).first() }

    assertEquals(listOf(R1, R2, R3), forA.map { it.recordingId })
    assertEquals(listOf(R1), forB.map { it.recordingId })
    val runs = runBlocking { database.analysisResults().observeFor(A, R2).first() }
    assertTrue(runs.isNotEmpty())
    assertTrue(runs.all { it.recordingId == R2 })
    assertEquals(
        listOf(R1, R4),
        runBlocking { database.remoteRecordings().observe(A).first() }.map { it.recordingId },
    )
  }

  @Test
  fun `the flat payload of an older version is read as the finished run it holds`() {
    // The flat shape the app cached before API v1: the result's fields sit beside the id.
    val file =
        File(root, "night-a1.json").apply {
          writeText(
              """
              {"id":"a1","session":"s1","model":"sleep-ecg","model_version":"1.0.0","status":"done",
               "contract_version":3,
               "stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":2,
                         "segments":[{"stage":"deep","count":2}]},
               "sleep_quality":{"score":77.0},
               "metrics":{"total_sleep_s":60.0},"warnings":["short"]}
              """
                  .trimIndent()
          )
        }

    val loaded = readLegacyAnalysis(file)

    assertNotNull(loaded)
    assertEquals("a1", loaded?.id)
    assertEquals("s1", loaded?.recording)
    assertEquals("sleep-ecg", loaded?.algorithm)
    assertEquals("1.0.0", loaded?.version)
    assertTrue(loaded?.isSucceeded == true)
    assertTrue(loaded?.result?.items?.isEmpty() == true)
    assertEquals("deep", loaded?.result?.stages?.segments?.single()?.stage)
    assertEquals(listOf("short"), loaded?.result?.warnings)
  }

  @Test
  fun `a payload file that is not an analysis is ignored`() {
    val file = File(root, "night-a1.json").apply { writeText("[1,2,3]") }

    assertNull(readLegacyAnalysis(file))
    assertNull(readLegacyAnalysis(File(root, "missing.json")))
  }

  @Test
  fun `a history file that cannot be read is ignored`() {
    val file = File(root, "history/remote-recordings.json").apply { writeJson("not json") }
    val cache = LegacyHistoryFile(file)

    assertNull(cache.load(A))
    assertTrue(cache.selected(A).isEmpty())
  }

  private fun assertUploadStates(expected: Map<String, Map<String, UploadRecord>>) {
    for ((account, records) in expected) {
      assertEquals(
          records,
          runBlocking { database.uploadStates().rows(account) }
              .map { it.toUploadRecord() }
              .associateBy { it.id },
      )
    }
  }

  /** The list each account holds, in the order the server sent it, and the run each recording shows. */
  private fun assertRemoteHistory(
      expected: Map<String, Pair<List<RecordingSummaryDto>, Map<String, String>>>,
  ) {
    for ((account, value) in expected) {
      val (items, selected) = value
      val recordings = runBlocking { database.remoteRecordings().rows(account) }
      val runs = runBlocking { database.remoteRecordings().analysisRows(account) }
      val migrated =
          RecordingSummaryListDto(
              recordings.map { row ->
                RecordingSummaryDto(
                    recording = row.toRecordingDto(),
                    analyses =
                        runs.filter { it.recordingId == row.recordingId }
                            .map { it.toAnalysisDto() }
                            .sortedBy(AnalysisDto::id),
                )
              }
          )
      assertEquals(
          RecordingSummaryListDto(
              items.map { it.copy(analyses = it.analyses.sortedBy(AnalysisDto::id)) }
          ),
          migrated,
      )
      assertEquals(
          selected,
          runBlocking { database.remoteRecordings().selections(account) }
              .associate { it.recordingKey to it.analysisId },
      )
    }
  }

  private fun assertAnalysisResults(expected: Map<Triple<String, String, String>, AnalysisDto>) {
    for ((key, payload) in expected) {
      val (account, recordingKey, analysisId) = key
      val migrated =
          runBlocking { database.analysisResults().row(account, recordingKey, analysisId) }
      assertNotNull("the database has no payload for $recordingKey/$analysisId", migrated)
      assertEquals(payload, migrated?.toAnalysisDto())
      assertNotNull(
          "the payload of $recordingKey/$analysisId is not marked as downloaded",
          migrated?.resultJson,
      )
    }
    for (account in expected.keys.map { it.first }.distinct()) {
      assertEquals(
          "every payload of $account is in the database",
          expected.keys.count { it.first == account },
          runBlocking { database.analysisResults().rows(account) }.size,
      )
    }
  }

  private fun assertPendingDeletes(expected: Map<String, PendingDelete>) {
    for ((account, delete) in expected) {
      assertEquals(
          mapOf(delete.remoteId to delete),
          runBlocking { database.pendingDeletes().rows(account) }
              .map { it.toPendingDelete() }
              .associateBy { it.remoteId },
      )
    }
  }

  /** The files an older version wrote, in one temporary directory. */
  private class Fixture(private val root: File, context: Context) {
    val recordings = File(root, "recordings")
    val historyFile = File(root, "history/remote-recordings.json")
    val analysisRoot = File(root, "analysis")
    val uploadPreferences: SharedPreferences =
        context.getSharedPreferences("uploads", Context.MODE_PRIVATE)
    val deletePreferences: SharedPreferences =
        context.getSharedPreferences("deletes", Context.MODE_PRIVATE)

    private val analysisOfA = File(analysisRoot, resultCacheDirectoryName(A))
    private val analysisOfB = File(analysisRoot, resultCacheDirectoryName(B))

    fun migration(active: String?, legacy: String? = null): LegacyFilesMigration =
        migration({ active }, legacy)

    fun migration(account: () -> String?, legacy: String? = null): LegacyFilesMigration =
        LegacyFilesMigration(
            recordingsRoot = recordings,
            uploadPreferences = uploadPreferences,
            historyFile = historyFile,
            analysisRoot = analysisRoot,
            deletePreferences = deletePreferences,
            account = account,
            legacyAccount = { legacy },
        )

    /** What the current version keeps: account-keyed documents and both payload key schemes. */
    fun writeCurrentFiles() {
      record(R1)
      record(R2)
      record(R3)
      File(recordings, "$R1/sync.json")
          .writeText(
              """
              {"accounts":{
                "$A":{"id":"$R1","onServer":true,"phase":"DONE","progress":1.0,"updatedAtMs":1000,
                      "uploadedAtMs":900,"confirmedOnServer":true,"manualAnalysisIds":["a1"]},
                "$B":{"id":"$R1","phase":"QUEUED","updatedAtMs":1100}}}
              """
                  .trimIndent()
          )
      File(recordings, "$R2/sync.json")
          .writeText(
              """
              {"accounts":{
                "$A":{"id":"$R2","onServer":true,"phase":"PROCESSING","progress":1.0,"updatedAtMs":1200,
                      "uploadedAtMs":1150,"keepRawOnPhone":true}}}
              """
                  .trimIndent()
          )
      File(recordings, "$R3/sync.json")
          .writeText(
              """{"accounts":{"$A":{"id":"$R3","phase":"FAILED","failure":"NETWORK","updatedAtMs":1300}}}"""
          )
      historyFile.writeJson(
          """
          {"accounts":{
            "$A":{
              "recordings":{"items":[
                {"recording":${recordingJson(R1, complete = true)},
                 "analyses":[${runJson("a1", R1, "baseline", "succeeded")}]},
                {"recording":${recordingJson(R4, complete = false)},"analyses":[]}
              ]},
              "selected":{"remote-$R4":"a9"}
            },
            "$B":{
              "recordings":{"items":[
                {"recording":${recordingJson(R5, complete = true)},
                 "analyses":[${runJson("a5", R5, "stager", "running")}]}
              ]},
              "selected":{}
            }
          }}
          """
              .trimIndent()
      )
      analysisOfA.mkdirs()
      analysisOfB.mkdirs()
      File(analysisOfA, "$R1.json").writeJson(payloadJson("a1", R1, "baseline", "1.0.0"))
      File(analysisOfA, "$R2-a2.json").writeJson(payloadJson("a2", R2, "baseline", "1.0.0"))
      File(analysisOfA, "$R2.json").writeJson(payloadJson("a2", R2, "baseline", "1.0.0"))
      File(analysisOfA, "remote-$R4-a9.json").writeJson(payloadJson("a9", R4, "stager", "2.0.0"))
      File(analysisOfB, "remote-$R5-a5.json").writeJson(payloadJson("a5", R5, "stager", "2.0.0"))
      deletePreferences
          .edit()
          .putString(
              "pending_remote_deletes",
              """
              {"accounts":{
                "$A":{"rf-1":{"remoteId":"rf-1","account":"$A","requestedAtMs":1500}},
                "$B":{"rf-2":{"remoteId":"rf-2","account":"$B","requestedAtMs":1600}}}}
              """
                  .trimIndent(),
          )
          .commit()
    }

    /**
     * What older versions kept: a sync document without accounts, the shared upload document under
     * a preference key, a single-account history document, a flat analysis file, and a delete queue
     * without accounts.
     */
    fun writeLegacyFiles() {
      record(R1)
      record(R2)
      record(R3)
      File(recordings, "$R1/sync.json")
          .writeText(
              """{"id":"$R1","onServer":true,"analysisId":"a1","phase":"DONE","progress":1.0,
               "epochSeconds":30,"segments":[{"stage":"light","count":4}],"sleepScore":81.0,
               "model":"baseline","modelVersion":"1.0.0","resultDownloaded":true,
               "updatedAtMs":1000,"uploadedAtMs":900,"keepRawOnPhone":true}"""
          )
      File(recordings, "$R2/sync.json")
          .writeText("""{"id":"$R2","phase":"QUEUED","updatedAtMs":1200}""")
      uploadPreferences
          .edit()
          .putString(
              "records",
              """
              {"$R3":{"id":"$R3","phase":"FAILED","failure":"NETWORK","errorDetail":"offline","updatedAtMs":1300},
               "remote-$R4":{"id":"remote-$R4","phase":"DONE","updatedAtMs":1400}}
              """
                  .trimIndent(),
          )
          .commit()
      historyFile.writeJson(
          """
          {"accountKey":"$OLD_KEY",
           "recordings":{"items":[{"recording":${recordingJson(R1, complete = true)},
                                   "analyses":[${runJson("a1", R1, "baseline", "succeeded")}]}]},
           "selected":{"$R1":"a1"}}
          """
              .trimIndent()
      )
      analysisRoot.mkdirs()
      // The flat format: the fields of the result beside the id and the recording, on the root.
      File(analysisRoot, "$R2.json").writeText(flatPayloadJson())
      deletePreferences
          .edit()
          .putString(
              "pending_remote_deletes",
              """{"rf-3":{"remoteId":"rf-3","requestedAtMs":1700}}""",
          )
          .commit()
    }

    private fun record(id: String) {
      File(recordings, id).mkdirs()
    }

    /** One recording of a saved list, as the fixture writes it and the row must reproduce it. */
    fun recordingDto(
        id: String,
        complete: Boolean,
        runs: List<AnalysisDto> = emptyList(),
    ): RecordingSummaryDto =
        RecordingSummaryDto(
            recording = rowJson.decodeFromString(recordingJson(id, complete)),
            analyses = runs,
        )

    /** One run of a saved list, as the fixture writes it and the row must reproduce it. */
    fun runDto(id: String, recordingId: String, algorithm: String, status: String): AnalysisDto =
        rowJson.decodeFromString(runJson(id, recordingId, algorithm, status))

    /** One downloaded payload, as the fixture writes it and the row must reproduce it. */
    fun payloadDto(id: String, recordingId: String, algorithm: String, version: String): AnalysisDto =
        rowJson.decodeFromString(payloadJson(id, recordingId, algorithm, version))

    /**
     * The flat payload an older version cached, read as the run it holds: the fields of the result
     * sit beside the id and the recording, and the file only exists for a finished run.
     */
    fun flatPayloadDto(recordingId: String): AnalysisDto =
        AnalysisDto(
            id = "a2",
            recording = recordingId,
            algorithm = "baseline",
            version = "1.0.0",
            status = "succeeded",
            result = rowJson.decodeFromString(flatPayloadJson()),
        )

    private fun recordingJson(id: String, complete: Boolean): String =
        """
        {"id":"$id","state":"${if (complete) "complete" else "uploading"}","device":"Polar H10",
         "started_at":"2026-10-23T22:30:00Z","ended_at":"2026-10-24T06:30:00Z","duration_s":28800.0,
         "complete":$complete,"size_bytes":12345,"created":"2026-10-23T22:30:00Z",
         "updated":"2026-10-24T06:31:00Z"}
        """
            .trimIndent()

    private fun runJson(id: String, recordingId: String, algorithm: String, status: String): String =
        """
        {"id":"$id","recording":"$recordingId","algorithm":"$algorithm","version":"1.0.0",
         "status":"$status","created":"2026-10-24T06:00:00Z","updated":"2026-10-24T07:00:00Z",
         "summary":{"stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":10,
                              "segments":[{"stage":"light","count":4},{"stage":"deep","count":6}]},
                    "scores":[{"id":"sleep_quality","value":81.0}]}}
        """
            .trimIndent()

    /** The flat payload an older version cached, kept where both the fixture and the test read it. */
    private fun flatPayloadJson(): String =
        """
        {"id":"a2","session":"$R2","contract_version":1,"model":"baseline","model_version":"1.0.0",
         "items":[{"kind":"number","id":"hr_mean","label":"Mean heart rate","unit":"bpm","value":57.0}]}
        """
            .trimIndent()

    /** The payload of a recording the fixture holds, as another account's cache would carry it. */
    fun strangerPayloadJson(): String = payloadJson("a1", R1, "sleep-ecg", "9.9.9")

    private fun payloadJson(
        id: String,
        recordingId: String,
        algorithm: String,
        version: String,
    ): String =
        """
        {"id":"$id","recording":"$recordingId","algorithm":"$algorithm","version":"$version",
         "status":"succeeded","created":"2026-10-24T06:00:00Z","updated":"2026-10-24T07:00:00Z",
         "summary":{"stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":10,
                              "segments":[{"stage":"light","count":10}]}},
         "result":{"contract_version":1,"model":"$algorithm","model_version":"$version",
                   "stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":10,
                             "segments":[{"stage":"light","count":10}],
                             "confidence":{"1":[0.9,0.1]}},
                   "items":[{"kind":"number","id":"hr_mean","label":"Mean heart rate","unit":"bpm",
                             "value":57.0,"sources":["hr"]}]}}
        """
            .trimIndent()
  }
}

private fun File.writeJson(text: String) {
  parentFile?.mkdirs()
  writeText(text)
}
