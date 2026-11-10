package com.rbmr.taigasleep.sync

import com.rbmr.taigasleep.backend.ApiCode
import com.rbmr.taigasleep.backend.RecordingDto
import com.rbmr.taigasleep.backend.RemoteFile
import com.rbmr.taigasleep.backend.StubTaigaClient
import com.rbmr.taigasleep.backend.TaigaClient
import com.rbmr.taigasleep.backend.TaigaConflictException
import com.rbmr.taigasleep.backend.TaigaHttpException
import com.rbmr.taigasleep.backend.TaigaNetworkException
import com.rbmr.taigasleep.sync.db.TaigaDatabase
import com.rbmr.taigasleep.sync.db.storeScope
import com.rbmr.taigasleep.sync.db.testDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ACCOUNT = "https://sleep.example|u1"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadEngineTest {
  private lateinit var dir: File
  private lateinit var database: TaigaDatabase
  private lateinit var scope: CoroutineScope
  private val accounts = MutableStateFlow<String?>(ACCOUNT)
  private val id = "20261023_223000-k3f9"
  private val meta = """{"format":"taiga-recording","version":1,"session_id":"$id"}"""

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("taiga-upload").toFile()
    File(dir, "meta.json").writeText(meta)
    database = testDatabase()
    scope = storeScope()
  }

  @After
  fun tearDown() {
    scope.cancel()
    database.close()
    dir.deleteRecursively()
  }

  /** The sync state of one upload, in the app's own tables. */
  private fun store() = UploadStore(database.uploadStates(), accounts, scope)

  private fun engine(
      client: TaigaClient,
      compress: Boolean = false,
      cacheDir: File? = null,
      onProgress: suspend (Float) -> Unit = {},
      onCreated: suspend () -> Unit = {},
      onCompleted: suspend () -> Unit = {},
  ) =
      UploadEngine(
          client = client,
          id = id,
          dir = dir,
          onProgress = onProgress,
          onCreated = onCreated,
          onCompleted = onCompleted,
          compress = compress,
          cacheDir = cacheDir,
      )

  /** The engine wired the way an upload reports into the sync state. */
  private fun storeEngine(client: TaigaClient, store: UploadStore) =
      engine(
          client = client,
          onProgress = { store.markUploading(id, it) },
          onCreated = { store.markOnServer(id) },
          onCompleted = { store.markUploadConfirmed(id) },
      )

  @Test
  fun theRecordingIsCreatedFromMetaJsonAtThePhonesId() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()
    var created = 0

    engine(client, onCreated = { created++ }).run()

    assertEquals(listOf(id to meta), client.puts)
    assertEquals(1, created)
    assertTrue(client.complete)
  }

  @Test
  fun fullProgressAloneDoesNotConfirmTheUpload() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val store = store()
    val client = FakeClient()
    client.completeError = TaigaNetworkException(IOException("offline"))

    runCatching { storeEngine(client, store).run() }

    val record = store.record(id)!!
    assertEquals(1f, record.progress, 0.001f)
    assertNull(record.uploadedAtMs)
    assertNull(record.uploadConfirmedAt())
  }

  @Test
  fun theServerAcceptingTheRecordingConfirmsTheUpload() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val store = store()

    storeEngine(FakeClient(), store).run()

    val record = store.record(id)!!
    assertEquals(1f, record.progress, 0.001f)
    assertTrue(record.uploadConfirmedAt() != null)
  }

  @Test
  fun aRecordingTheServerAlreadyHasIsConfirmedWithoutCompletingItAgain() = runBlocking {
    val store = store()
    val client = FakeClient(sealed = true)

    storeEngine(client, store).run()

    assertEquals(0, client.completeCalls)
    assertTrue(store.record(id)!!.uploadConfirmedAt() != null)
  }

  @Test
  fun resumesFromTheLengthTheServerAlreadyHas() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10) { it.toByte() })
    val client = FakeClient()
    client.store("hr.log", ByteArray(4) { it.toByte() })
    var progress = 0f

    engine(client, onProgress = { progress = it }).run()

    assertEquals(listOf(4L to 6), client.appends.map { it.offset to it.bytes.size })
    assertArrayEquals(ByteArray(6) { (it + 4).toByte() }, client.appends.single().bytes)
    assertArrayEquals(ByteArray(10) { it.toByte() }, client.stored.getValue("hr.log"))
    assertEquals(1f, progress)
    assertTrue(client.complete)
  }

  @Test
  fun eachFileIsAskedForItsOwnOffset() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    File(dir, "ecg.log").writeBytes(ByteArray(8))
    val client = FakeClient()
    client.store("ecg.log", ByteArray(8))

    engine(client).run()

    assertEquals(listOf("hr.log", "ecg.log"), client.heads)
    assertEquals(listOf("hr.log"), client.appends.map { it.file })
  }

  @Test
  fun aConflictIsRecoveredFromTheOffsetTheServerReports() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10) { it.toByte() })
    val client = FakeClient()
    client.moveOffsetOnce = 1

    engine(client).run()

    assertEquals(listOf(0L, 1L), client.appendAttempts)
    assertEquals(1L, client.appends.single().offset)
    assertEquals(9, client.appends.single().bytes.size)
    assertTrue(client.complete)
  }

  @Test
  fun aConflictWithoutTheOffsetIsRecoveredByAskingTheServer() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10) { it.toByte() })
    val client = FakeClient()
    client.moveOffsetOnce = 1
    client.reportOffset = false

    engine(client).run()

    assertEquals(listOf(0L, 1L), client.appendAttempts)
    // One ask before the first chunk, one after the conflict.
    assertEquals(2, client.heads.size)
    assertTrue(client.complete)
  }

  @Test
  fun aConflictThatIsNotAboutTheOffsetIsNotRetried() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()
    client.appendError = TaigaConflictException(409, "sealed", ApiCode.RECORDING_COMPLETE)

    val error = runCatching { engine(client).run() }.exceptionOrNull()

    assertTrue(error is TaigaConflictException)
    assertEquals(ApiCode.RECORDING_COMPLETE, (error as TaigaConflictException).code)
    assertEquals(1, client.appendAttempts.size)
  }

  @Test
  fun aServerThatKeepsMovingTheOffsetGivesUpAfterACoupleOfConflicts() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()
    client.alwaysConflict = true

    val error = runCatching { engine(client).run() }.exceptionOrNull()

    assertTrue(error is TaigaConflictException)
    assertEquals(3, client.appendAttempts.size)
    assertFalse(client.complete)
  }

  @Test
  fun aSealedRecordingIsOnlyReadBack() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient(sealed = true)
    client.store("hr.log", ByteArray(10))

    engine(client).run()

    assertTrue(client.appends.isEmpty())
    assertTrue(client.heads.isEmpty())
    assertEquals(0, client.completeCalls)
    assertEquals(1, client.gets)
  }

  @Test
  fun aSealedRecordingNeedsNoLocalStreamFiles() = runBlocking {
    // The raw files may have been removed from the phone after the upload.
    val client = FakeClient(sealed = true)

    engine(client).run()

    assertEquals(0, client.completeCalls)
  }

  @Test
  fun anotherConflictOnCreationFails() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()
    client.putError = TaigaConflictException(409, "another account", "id_taken")

    val error = runCatching { engine(client).run() }.exceptionOrNull()

    assertEquals("id_taken", (error as TaigaConflictException).code)
    assertTrue(client.appends.isEmpty())
  }

  @Test
  fun aRecordingWithoutStreamFilesIsNeverCompleted() = runBlocking {
    val client = FakeClient()
    try {
      engine(client).run()
      throw AssertionError("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("stream files"))
    }
    assertEquals(0, client.completeCalls)
  }

  @Test
  fun aMissingMetaFileFailsBeforeTouchingTheServer() = runBlocking {
    File(dir, "meta.json").delete()
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()

    try {
      engine(client).run()
      throw AssertionError("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("meta.json"))
    }
    assertTrue(client.puts.isEmpty())
  }

  @Test
  fun aServerCopyLongerThanThePhonesFails() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()
    client.store("hr.log", ByteArray(20))

    try {
      engine(client).run()
      throw AssertionError("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("20 bytes"))
    }
  }

  @Test
  fun aChunkThatDoesNotGrowTheFileIsAFailureNotALoop() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()
    client.ignoreAppends = true

    try {
      engine(client).run()
      throw AssertionError("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("did not accept"))
    }
    assertEquals(1, client.appendAttempts.size)
  }

  @Test
  fun aFileThatTheServerCapsAtASmallerChunkIsSentInSmallerChunks() = runBlocking {
    val data = ByteArray(3 * 1024 * 1024 + 7) { ((it * 31) % 251).toByte() }
    File(dir, "hr.log").writeBytes(data)
    val client = FakeClient()
    client.maxChunk = 1024 * 1024

    engine(client).run()

    assertTrue(client.appends.all { it.bytes.size <= 1024 * 1024 })
    assertArrayEquals(data, client.stored.getValue("hr.log"))
    assertTrue(client.complete)
  }

  @Test
  fun anOverlargeChunkThatCannotBeShrunkFails() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))
    val client = FakeClient()
    client.maxChunk = 0

    val error = runCatching { engine(client).run() }.exceptionOrNull()

    assertEquals(ApiCode.CHUNK_TOO_LARGE, (error as TaigaHttpException).code)
  }

  @Test
  fun compressedUploadSendsOneMemberPerChunkAndResumes() = runBlocking {
    val data = ByteArray(3 * 1024 * 1024 + 123) { ((it * 31) % 251).toByte() }
    File(dir, "hr.log").writeBytes(data)
    val cache = Files.createTempDirectory("taiga-cache").toFile()
    val accepted = uploadOneChunkThenLoseTheNetwork(cache)

    // The next attempt starts at the server's compressed offset, which HEAD reports with its encoding.
    val second = FakeClient()
    second.store("hr.log", accepted, encoding = "gzip")
    engine(second, compress = true, cacheDir = cache).run()

    assertEquals(1, second.appends.size)
    assertEquals(accepted.size.toLong(), second.appends.single().offset)
    assertEquals("gzip", second.appends.single().encoding)
    assertArrayEquals(data, gunzip(second.stored.getValue("hr.log")))
    assertTrue(second.complete)
    assertTrue(!cache.exists())
  }

  @Test
  fun aFileThatHoldsCompressedBytesKeepsUsingThemWhenTheSettingIsOff() = runBlocking {
    val data = ByteArray(3 * 1024 * 1024 + 123) { ((it * 31) % 251).toByte() }
    File(dir, "hr.log").writeBytes(data)
    val cache = Files.createTempDirectory("taiga-cache").toFile()
    val accepted = uploadOneChunkThenLoseTheNetwork(cache)
    val client = FakeClient()
    client.store("hr.log", accepted, encoding = "gzip")

    engine(client, compress = false, cacheDir = cache).run()

    assertEquals(listOf("gzip"), client.appends.map { it.encoding })
    assertArrayEquals(data, gunzip(client.stored.getValue("hr.log")))
  }

  /** A first attempt whose connection dies after the server accepted one compressed chunk. */
  private fun uploadOneChunkThenLoseTheNetwork(cache: File): ByteArray {
    val first = FakeClient()
    first.failAfterAppends = 1
    try {
      runBlocking { engine(first, compress = true, cacheDir = cache).run() }
      throw AssertionError("expected TaigaNetworkException")
    } catch (_: TaigaNetworkException) {
      // expected
    }
    assertEquals(1, first.appends.size)
    assertTrue(first.appends.all { it.encoding == "gzip" })
    return first.appends.single().bytes
  }

  @Test
  fun aFileThatStartedPlainStaysPlainWhenCompressionIsTurnedOn() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10) { it.toByte() })
    val client = FakeClient()
    client.store("hr.log", ByteArray(4) { it.toByte() })

    engine(client, compress = true, cacheDir = Files.createTempDirectory("taiga-cache").toFile())
        .run()

    assertEquals(listOf(null), client.appends.map { it.encoding })
    assertArrayEquals(ByteArray(10) { it.toByte() }, client.stored.getValue("hr.log"))
  }

  @Test
  fun eachNewFileFollowsTheSettingWhileStartedFilesKeepTheirEncoding() = runBlocking {
    val hr = ByteArray(4000) { (it % 89).toByte() }
    File(dir, "hr.log").writeBytes(hr)
    File(dir, "ecg.log").writeBytes(ByteArray(300) { 1 })
    val client = FakeClient()
    client.store("ecg.log", ByteArray(100) { 1 })

    engine(client, compress = true, cacheDir = Files.createTempDirectory("taiga-cache").toFile())
        .run()

    assertEquals(
        mapOf("hr.log" to "gzip", "ecg.log" to null),
        client.appends.associate { it.file to it.encoding },
    )
  }

  @Test
  fun compressedChunksStayWithinWhatTheServerAcceptsEvenForIncompressibleData() = runBlocking {
    val data = Random(7).nextBytes(2 * 1024 * 1024 + 99)
    File(dir, "hr.log").writeBytes(data)
    val client = FakeClient()
    client.maxChunk = 512 * 1024
    val cache = Files.createTempDirectory("taiga-cache").toFile()

    engine(client, compress = true, cacheDir = cache).run()

    assertTrue(client.appends.all { it.bytes.size <= 512 * 1024 })
    assertArrayEquals(data, gunzip(client.stored.getValue("hr.log")))
    assertTrue(!cache.exists())
  }

  @Test
  fun aCompressedConflictResumesFromTheServersOffset() = runBlocking {
    val data = ByteArray(3 * 1024 * 1024 + 5) { ((it * 17) % 253).toByte() }
    File(dir, "hr.log").writeBytes(data)
    val cache = Files.createTempDirectory("taiga-cache").toFile()
    val client = FakeClient()
    // Another attempt appends the same first member before ours arrives.
    client.otherAttemptWinsTheFirstAppend = true

    engine(client, compress = true, cacheDir = cache).run()

    // The first request conflicted at 0; what follows continues after the member that was already there.
    assertEquals(0L, client.appendAttempts.first())
    assertTrue(client.appends.first().offset > 0)
    assertArrayEquals(data, gunzip(client.stored.getValue("hr.log")))
    assertTrue(client.complete)
    assertTrue(!cache.exists())
  }

  @Test
  fun aCompressedCacheThatCannotExplainTheServerFailsClearly() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(5000))
    val cache = Files.createTempDirectory("taiga-cache").toFile()
    val client = FakeClient()
    client.store("hr.log", ByteArray(77), encoding = "gzip")

    try {
      engine(client, compress = true, cacheDir = cache).run()
      throw AssertionError("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("cache"))
    }
  }

  @Test
  fun aCompressedUploadNeedsACacheDirectory() = runBlocking {
    File(dir, "hr.log").writeBytes(ByteArray(10))

    try {
      engine(FakeClient(), compress = true, cacheDir = null).run()
      fail("expected UploadException")
    } catch (expected: UploadException) {
      assertTrue(expected.message.orEmpty().contains("cache directory"))
    }
  }

  @Test
  fun gzipMembersAreDeterministicAndValid() {
    val data = ByteArray(10_000) { (it % 97).toByte() }
    val first = gzipMember(data)
    val second = gzipMember(data)

    assertArrayEquals(first, second)
    assertEquals(0x1f, first[0].toInt() and 0xff)
    assertEquals(0x8b, first[1].toInt() and 0xff)
    assertArrayEquals(data, gunzip(first))
  }

  private fun gunzip(bytes: ByteArray): ByteArray =
      GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

  private class Append(
      val file: String,
      val offset: Long,
      val bytes: ByteArray,
      val encoding: String?,
  )

  /** A server that behaves like docs/api.md for the calls an upload makes. */
  private class FakeClient(sealed: Boolean = false) : StubTaigaClient() {
    var complete = sealed
    val stored = mutableMapOf<String, ByteArray>()
    private val encodings = mutableMapOf<String, String>()

    val puts = mutableListOf<Pair<String, String>>()
    val heads = mutableListOf<String>()
    val appends = mutableListOf<Append>()
    val appendAttempts = mutableListOf<Long>()
    var completeCalls = 0
    var gets = 0

    var putError: Exception? = null
    var appendError: Exception? = null
    var completeError: Exception? = null
    var failAfterAppends = -1
    var moveOffsetOnce = -1L
    var reportOffset = true
    var alwaysConflict = false
    var ignoreAppends = false
    var otherAttemptWinsTheFirstAppend = false
    var maxChunk = Int.MAX_VALUE

    fun store(file: String, bytes: ByteArray, encoding: String = "") {
      stored[file] = bytes
      if (encoding.isNotEmpty()) encodings[file] = encoding
    }

    override suspend fun putRecording(id: String, metaJson: String): RecordingDto {
      puts += id to metaJson
      putError?.let { throw it }
      if (complete) throw TaigaConflictException(409, "sealed", ApiCode.RECORDING_COMPLETE)
      return recording(id)
    }

    override suspend fun getRecording(id: String): RecordingDto {
      gets++
      return recording(id)
    }

    override suspend fun headFile(id: String, file: String): RemoteFile? {
      heads += file
      val bytes = stored[file] ?: return null
      return RemoteFile(size = bytes.size.toLong(), encoding = encodings[file].orEmpty())
    }

    override suspend fun appendFile(
        id: String,
        file: String,
        offset: Long,
        bytes: ByteArray,
        encoding: String?,
    ): Long {
      appendAttempts += offset
      appendError?.let { throw it }
      if (bytes.size > maxChunk) {
        throw TaigaHttpException(413, "a chunk is too large", ApiCode.CHUNK_TOO_LARGE)
      }
      if (otherAttemptWinsTheFirstAppend) {
        otherAttemptWinsTheFirstAppend = false
        stored[file] = bytes.copyOf()
        if (encoding == "gzip") encodings[file] = "gzip"
      }
      if (moveOffsetOnce >= 0) {
        // Someone else appended meanwhile.
        stored[file] = ByteArray(moveOffsetOnce.toInt())
        moveOffsetOnce = -1
        throw conflict(file)
      }
      val current = stored[file]?.size?.toLong() ?: 0L
      if (alwaysConflict || offset != current) throw conflict(file)
      if (failAfterAppends == 0) {
        failAfterAppends = -1
        throw TaigaNetworkException(IOException("offline"))
      }
      if (failAfterAppends > 0) failAfterAppends--
      if (ignoreAppends) return current
      stored[file] = (stored[file] ?: ByteArray(0)) + bytes
      if (encoding == "gzip") encodings[file] = "gzip"
      appends += Append(file, offset, bytes, encoding)
      return stored.getValue(file).size.toLong()
    }

    private fun conflict(file: String) =
        TaigaConflictException(
            409,
            "offset mismatch",
            ApiCode.OFFSET_MISMATCH,
            uploadOffset = if (reportOffset) stored[file]?.size?.toLong() ?: 0L else null,
        )

    override suspend fun completeRecording(id: String): RecordingDto {
      completeCalls++
      completeError?.let { throw it }
      complete = true
      return recording(id)
    }

    private fun recording(id: String) = RecordingDto(id = id, state = "stopped", complete = complete)
  }
}
