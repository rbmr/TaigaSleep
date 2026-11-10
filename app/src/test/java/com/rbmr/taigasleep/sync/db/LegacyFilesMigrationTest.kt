package com.rbmr.taigasleep.sync.db

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.rbmr.taigasleep.backend.AnalysisDto
import com.rbmr.taigasleep.backend.RecordingSummaryDto
import com.rbmr.taigasleep.backend.RecordingSummaryListDto
import com.rbmr.taigasleep.sync.PendingDeleteStore
import com.rbmr.taigasleep.sync.PrefsPendingDeleteStorage
import com.rbmr.taigasleep.sync.RecordingDirectoryStorage
import com.rbmr.taigasleep.sync.RemoteHistoryCache
import com.rbmr.taigasleep.sync.ResultCache
import com.rbmr.taigasleep.sync.UploadStore
import com.rbmr.taigasleep.sync.resultCacheDirectoryName
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    root = Files.createTempDirectory("taiga-legacy").toFile()
    database =
        Room.inMemoryDatabaseBuilder(context, TaigaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    fixture = Fixture(root, context)
  }

  @After
  fun tearDown() {
    database.close()
    root.deleteRecursively()
  }

  @Test
  fun `every store's state moves into the database`() {
    fixture.writeCurrentFiles()

    assertTrue(runBlocking { fixture.migration(A).migrate(database) })

    assertUploadStatesMatchTheStore(listOf(A, B))
    assertRemoteHistoryMatchesTheStore(listOf(A, B))
    assertAnalysisResultsMatchTheStore(
        listOf(
            Triple(A, R1, "a1"),
            Triple(A, R2, "a2"),
            Triple(A, "remote-$R4", "a9"),
            Triple(B, "remote-$R5", "a5"),
        )
    )
    assertPendingDeletesMatchTheStore(listOf(A, B))
  }

  @Test
  fun `the formats an older version wrote move too`() {
    fixture.writeLegacyFiles()

    assertTrue(runBlocking { fixture.migration(A, legacy = OLD_KEY).migrate(database) })

    assertUploadStatesMatchTheStore(listOf(A))
    assertRemoteHistoryMatchesTheStore(listOf(A), legacy = OLD_KEY)
    assertAnalysisResultsMatchTheStore(listOf(Triple(A, R2, "a2")))
    assertPendingDeletesMatchTheStore(listOf(A))
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
  fun `without a session nothing is written and the marker stays empty`() {
    fixture.writeCurrentFiles()

    assertFalse(runBlocking { fixture.migration(null).migrate(database) })

    assertEquals(0, runBlocking { database.migrations().runs("files-v1") })
    assertTrue(runBlocking { database.uploadStates().rows(A) }.isEmpty())
    assertTrue(runBlocking { database.remoteRecordings().rows(A) }.isEmpty())
    assertTrue(runBlocking { database.remoteRecordings().analysisRows(A) }.isEmpty())
    assertTrue(runBlocking { database.analysisResults().rows(A) }.isEmpty())
    assertTrue(runBlocking { database.pendingDeletes().rows(A) }.isEmpty())
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

  private fun assertUploadStatesMatchTheStore(accounts: List<String>) {
    val store = UploadStore(fixture.uploadStorage(), account = { holder.active })
    for (account in accounts) {
      holder.active = account
      store.refreshAccount()
      val stored = store.records.value
      assertTrue("the fixture has no upload state for $account", stored.isNotEmpty())
      assertEquals(
          stored,
          runBlocking { database.uploadStates().rows(account) }
              .map { it.toUploadRecord() }
              .associateBy { it.id },
      )
    }
  }

  private fun assertRemoteHistoryMatchesTheStore(accounts: List<String>, legacy: String? = null) {
    val cache = RemoteHistoryCache(fixture.historyFile, account = { A })
    // The app moves a list saved under the address and the email to the account key on start.
    if (legacy != null) cache.adopt(legacy, A)
    for (account in accounts) {
      val stored = cache.load(account)
      assertNotNull("the fixture has no list for $account", stored)
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
      val expected =
          stored!!.copy(
              items = stored.items.map { it.copy(analyses = it.analyses.sortedBy(AnalysisDto::id)) }
          )
      assertEquals(expected, migrated)
      assertEquals(
          cache.selected(account),
          runBlocking { database.remoteRecordings().selections(account) }
              .associate { it.recordingKey to it.analysisId },
      )
    }
  }

  private fun assertAnalysisResultsMatchTheStore(runs: List<Triple<String, String, String>>) {
    val results = ResultCache(fixture.analysisRoot)
    for ((account, key, analysisId) in runs) {
      val stored = results.load(account, key, analysisId)
      assertNotNull("the fixture has no payload for $key/$analysisId", stored)
      val migrated = runBlocking { database.analysisResults().row(account, key, analysisId) }
      assertEquals(stored, migrated?.toAnalysisDto())
      assertNotNull(
          "the payload of $key/$analysisId is not marked as downloaded",
          migrated?.resultJson,
      )
    }
    for (account in runs.map { it.first }.distinct()) {
      assertEquals(
          "every payload of $account is in the database",
          runs.count { it.first == account },
          runBlocking { database.analysisResults().rows(account) }.size,
      )
    }
  }

  private fun assertPendingDeletesMatchTheStore(accounts: List<String>) {
    val store =
        PendingDeleteStore(
            PrefsPendingDeleteStorage(fixture.deletePreferences),
            account = { holder.active },
        )
    for (account in accounts) {
      holder.active = account
      store.refreshAccount()
      val stored = store.pending.value
      assertTrue("the fixture has no queued delete for $account", stored.isNotEmpty())
      assertEquals(
          stored,
          runBlocking { database.pendingDeletes().rows(account) }
              .map { it.toPendingDelete() }
              .associateBy { it.remoteId },
      )
    }
  }

  /** The session the stores read the active account from. */
  private val holder = ActiveAccount()

  private class ActiveAccount {
    var active: String? = null
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
        LegacyFilesMigration(
            recordingsRoot = recordings,
            uploadPreferences = uploadPreferences,
            historyFile = historyFile,
            analysisRoot = analysisRoot,
            deletePreferences = deletePreferences,
            account = { active },
            legacyAccount = { legacy },
        )

    fun uploadStorage() = RecordingDirectoryStorage(recordings, uploadPreferences)

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
                {"recording":${recording(R1, complete = true)},
                 "analyses":[${run("a1", R1, "baseline", "succeeded")}]},
                {"recording":${recording(R4, complete = false)},"analyses":[]}
              ]},
              "selected":{"remote-$R4":"a9"}
            },
            "$B":{
              "recordings":{"items":[
                {"recording":${recording(R5, complete = true)},
                 "analyses":[${run("a5", R5, "stager", "running")}]}
              ]},
              "selected":{}
            }
          }}
          """
              .trimIndent()
      )
      analysisOfA.mkdirs()
      analysisOfB.mkdirs()
      File(analysisOfA, "$R1.json").writeJson(payload("a1", R1, "baseline", "1.0.0"))
      File(analysisOfA, "$R2-a2.json").writeJson(payload("a2", R2, "baseline", "1.0.0"))
      File(analysisOfA, "$R2.json").writeJson(payload("a2", R2, "baseline", "1.0.0"))
      File(analysisOfA, "remote-$R4-a9.json").writeJson(payload("a9", R4, "stager", "2.0.0"))
      File(analysisOfB, "remote-$R5-a5.json").writeJson(payload("a5", R5, "stager", "2.0.0"))
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
              """{"id":"$R1","onServer":true,"phase":"DONE","progress":1.0,"updatedAtMs":1000}"""
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
           "recordings":{"items":[{"recording":${recording(R1, complete = true)},
                                   "analyses":[${run("a1", R1, "baseline", "succeeded")}]}]},
           "selected":{"$R1":"a1"}}
          """
              .trimIndent()
      )
      analysisRoot.mkdirs()
      // The flat format: the fields of the result beside the id and the recording, on the root.
      File(analysisRoot, "$R2.json")
          .writeText(
              """
              {"id":"a2","session":"$R2","contract_version":1,"model":"baseline","model_version":"1.0.0",
               "items":[{"kind":"number","id":"hr_mean","label":"Mean heart rate","unit":"bpm","value":57.0}]}
              """
                  .trimIndent()
          )
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

    private fun recording(id: String, complete: Boolean): String =
        """
        {"id":"$id","state":"${if (complete) "complete" else "uploading"}","device":"Polar H10",
         "started_at":"2026-10-23T22:30:00Z","ended_at":"2026-10-24T06:30:00Z","duration_s":28800.0,
         "complete":$complete,"size_bytes":12345,"created":"2026-10-23T22:30:00Z",
         "updated":"2026-10-24T06:31:00Z"}
        """
            .trimIndent()

    private fun run(id: String, recordingId: String, algorithm: String, status: String): String =
        """
        {"id":"$id","recording":"$recordingId","algorithm":"$algorithm","version":"1.0.0",
         "status":"$status","created":"2026-10-24T06:00:00Z","updated":"2026-10-24T07:00:00Z",
         "summary":{"stages":{"epoch_seconds":30,"start_unix_ms":1000,"count":10,
                              "segments":[{"stage":"light","count":4},{"stage":"deep","count":6}]},
                    "scores":[{"id":"sleep_quality","value":81.0}]}}
        """
            .trimIndent()

    private fun payload(
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
