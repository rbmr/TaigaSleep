package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The recording, analysis and algorithm calls of docs/api.md. Recordings and analyses are read and
 * written through PocketBase's record API; the resumable file uploads, the analysis queue and the
 * access tokens keep their own routes. The interface is separate so the upload state machine can be
 * tested without Android or a live server.
 */
interface TaigaClient {
  /** Creates the recording at the phone's id, or replaces its metadata while it is incomplete. */
  suspend fun putRecording(id: String, metaJson: String): RecordingDto

  suspend fun getRecording(id: String): RecordingDto

  /** What the server holds of one file, or null when no chunk of it has arrived yet. */
  suspend fun headFile(id: String, file: String): RemoteFile?

  /**
   * Appends one chunk at [offset] and returns the file's new stored size. [encoding] is `gzip` for
   * a chunk that is one complete gzip member. A wrong offset throws [TaigaConflictException] with
   * the size the server has.
   */
  suspend fun appendFile(
      id: String,
      file: String,
      offset: Long,
      bytes: ByteArray,
      encoding: String? = null,
  ): Long

  /** Seals the recording once every file has been sent. */
  suspend fun completeRecording(id: String): RecordingDto

  /**
   * Asks for one analysis. Asking again for a failed one replaces it, which is how a retry is made,
   * so a retry has to repeat the failed run's [params].
   */
  suspend fun requestAnalysis(
      recordingId: String,
      algorithm: String,
      version: String,
      params: JsonObject? = null,
  ): AnalysisDto

  suspend fun getAlgorithms(): AlgorithmListDto

  suspend fun deleteRecording(id: String)

  /** One analysis with its summary and, once it succeeded, its full result. */
  suspend fun getAnalysis(analysisId: String): AnalysisDto

  suspend fun listFiles(id: String): RecordingFileListDto

  /** Downloads one raw recording file. The server inflates its compressed copy when needed. */
  suspend fun downloadFile(id: String, file: String, target: File)

  suspend fun listRecordings(limit: Int = 100): RecordingListDto

  /**
   * One page of analyses, newest first, without their results. All of the account's analyses, or
   * those of [recording] only.
   */
  suspend fun listAnalyses(
      recording: String? = null,
      page: Int = 1,
      perPage: Int = 200,
  ): AnalysisListDto

  /** The account's read-only access tokens. A token itself is never in a list. */
  suspend fun listAccessTokens(): AccessTokenListDto

  /** Creates a read-only access token; the answer is the only time its secret is visible. */
  suspend fun createAccessToken(name: String): AccessTokenDto

  /** Revokes a token, which stops it working at once. */
  suspend fun deleteAccessToken(id: String)
}

/**
 * The newest [limit] recordings with their analyses, which is what History shows. The analyses
 * come from the account's paged analysis list instead of one request per recording.
 */
suspend fun TaigaClient.recordingSummaries(limit: Int = 100): RecordingSummaryListDto {
  val recordings = listRecordings(limit).items
  val wanted = recordings.map { it.id }.toSet()
  val byRecording = mutableMapOf<String, MutableList<AnalysisDto>>()
  for (analysis in allAnalyses(max = MAX_LISTED_ANALYSES)) {
    if (analysis.recording !in wanted) continue
    byRecording.getOrPut(analysis.recording) { mutableListOf() }.add(analysis)
  }
  return RecordingSummaryListDto(
      recordings.map { RecordingSummaryDto(it, byRecording[it.id].orEmpty()) }
  )
}

/**
 * Every analysis the caller may see, newest first, read page by page. [recording] limits the answer
 * to one recording's runs, and [max] bounds the read so a wrong total cannot keep the loop alive.
 */
suspend fun TaigaClient.allAnalyses(
    recording: String? = null,
    max: Int = MAX_LISTED_ANALYSES,
): List<AnalysisDto> {
  val items = mutableListOf<AnalysisDto>()
  var page = 1
  while (items.size < max) {
    val listed = listAnalyses(recording = recording, page = page)
    items += listed.items
    if (listed.items.isEmpty() || page >= listed.totalPages) break
    page++
  }
  return if (items.size > max) items.take(max) else items
}

private const val MAX_LISTED_ANALYSES = 2000

/**
 * The fields a list needs, so it stays as small as the old list: the recording's meta.json and file
 * bookkeeping are only read one by one.
 */
private const val RECORDING_LIST_FIELDS =
    "id,state,device,started_at,ended_at,duration_s,complete,stored_bytes,created,updated"

/** A list carries the summary but not the full result, which is fetched only when it is shown. */
private const val ANALYSIS_LIST_FIELDS =
    "id,recording,algorithm,version,params,status,error,attempts,created,updated,finished_at,summary"

/** The most records the record API documents for one page. */
private const val MAX_PER_PAGE = 500

/** The error `code` values of docs/api.md that the app acts on. */
object ApiCode {
  const val OFFSET_MISMATCH = "offset_mismatch"
  const val RECORDING_COMPLETE = "recording_complete"
  const val RECORDING_IN_PROGRESS = "recording_in_progress"
  const val CHUNK_TOO_LARGE = "chunk_too_large"
  const val STORAGE_QUOTA_EXCEEDED = "storage_quota_exceeded"
}

open class TaigaException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TaigaNetworkException(cause: Throwable) : TaigaException("network unavailable", cause)

class TaigaMalformedException(cause: Throwable) : TaigaException("malformed response", cause)

/**
 * An error answer. [code] is the stable identifier from the `data.code` field of PocketBase's error
 * body, or empty when the body has none.
 */
open class TaigaHttpException(val status: Int, message: String, val code: String = "") :
    TaigaException(message)

class TaigaUnauthorizedException(status: Int, message: String, code: String = "") :
    TaigaHttpException(status, message, code)

/**
 * The request conflicts with the server's state; [code] says how. For [ApiCode.OFFSET_MISMATCH],
 * [uploadOffset] is the stored size the caller can resume from.
 */
class TaigaConflictException(
    status: Int,
    message: String,
    code: String = "",
    val uploadOffset: Long? = null,
) : TaigaHttpException(status, message, code)

class OkHttpTaigaApi(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
    private val userAgent: String = "taiga-sleep-android/${BuildConfig.VERSION_NAME}",
) : TaigaClient {
  private val json =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
      }

  override suspend fun putRecording(id: String, metaJson: String): RecordingDto {
    val payload =
        buildJsonObject {
              put("id", id)
              put("meta", parseMeta(metaJson))
            }
            .toString()
    return decode(
        execute(request(RECORDINGS).post(payload.toRequestBody(JSON_MEDIA_TYPE)).build())
    )
  }

  override suspend fun getRecording(id: String): RecordingDto =
      decode(execute(request(recordingPath(id)).get().build()))

  override suspend fun headFile(id: String, file: String): RemoteFile? =
      try {
        call(request("/v1/recordings/${path(id)}/files/${path(file)}").head().build()) { response ->
          val size =
              response.header(UPLOAD_OFFSET)?.toLongOrNull()
                  ?: throw TaigaMalformedException(IllegalStateException("missing $UPLOAD_OFFSET"))
          RemoteFile(size = size, encoding = response.header("Content-Encoding").orEmpty())
        }
      } catch (e: TaigaHttpException) {
        // A file that has no chunk yet is not an error: the upload starts at 0.
        if (e.status == 404) null else throw e
      }

  override suspend fun appendFile(
      id: String,
      file: String,
      offset: Long,
      bytes: ByteArray,
      encoding: String?,
  ): Long {
    val builder =
        request("/v1/recordings/${path(id)}/files/${path(file)}")
            .patch(bytes.toRequestBody(OCTET))
            .header(UPLOAD_OFFSET, offset.toString())
    if (!encoding.isNullOrBlank()) builder.header("Content-Encoding", encoding)
    return call(builder.build()) { response ->
      response.header(UPLOAD_OFFSET)?.toLongOrNull()
          ?: throw TaigaMalformedException(IllegalStateException("missing $UPLOAD_OFFSET"))
    }
  }

  override suspend fun completeRecording(id: String): RecordingDto =
      decode(
          execute(
              request(recordingPath(id))
                  .patch("""{"complete":true}""".toRequestBody(JSON_MEDIA_TYPE))
                  .build()
          )
      )

  override suspend fun requestAnalysis(
      recordingId: String,
      algorithm: String,
      version: String,
      params: JsonObject?,
  ): AnalysisDto {
    val payload =
        buildJsonObject {
              put("recording", recordingId)
              put("algorithm", algorithm)
              if (version.isNotBlank()) put("version", version)
              if (params != null) put("params", params)
            }
            .toString()
    return decode(
        execute(request(ANALYSES).post(payload.toRequestBody(JSON_MEDIA_TYPE)).build())
    )
  }

  override suspend fun getAlgorithms(): AlgorithmListDto =
      decode(execute(request("/v1/algorithms").get().build()))

  override suspend fun deleteRecording(id: String) {
    execute(request(recordingPath(id)).delete().build())
  }

  override suspend fun getAnalysis(analysisId: String): AnalysisDto =
      decode(execute(request(analysisPath(analysisId)).get().build()))

  override suspend fun listFiles(id: String): RecordingFileListDto =
      decode(execute(request("/v1/recordings/${path(id)}/files").get().build()))

  override suspend fun downloadFile(id: String, file: String, target: File) {
    // The server stores a compressed file as a sequence of gzip members. Asking for identity makes
    // it inflate them itself, because OkHttp's transparent gzip handling reads a single member.
    val request =
        request("/v1/recordings/${path(id)}/files/${path(file)}")
            .get()
            .header("Accept-Encoding", "identity")
            .build()
    try {
      call(request) { response ->
        val body = response.body
        if (body == null) throw TaigaMalformedException(IllegalStateException("empty response body"))
        target.outputStream().use { out -> body.byteStream().copyTo(out) }
      }
    } catch (e: TaigaException) {
      target.delete()
      throw e
    }
  }

  override suspend fun listRecordings(limit: Int): RecordingListDto {
    val query =
        mapOf(
            "page" to "1",
            "perPage" to limit.coerceIn(1, MAX_PER_PAGE).toString(),
            "sort" to "-started_at",
            "fields" to RECORDING_LIST_FIELDS,
        )
    return decode(execute(request(RECORDINGS, query).get().build()))
  }

  override suspend fun listAnalyses(recording: String?, page: Int, perPage: Int): AnalysisListDto {
    val query =
        buildMap {
          put("page", page.coerceAtLeast(1).toString())
          put("perPage", perPage.coerceIn(1, MAX_PER_PAGE).toString())
          put("sort", "-created")
          put("fields", ANALYSIS_LIST_FIELDS)
          if (recording != null) put("filter", "recording = \"$recording\"")
        }
    return decode(execute(request(ANALYSES, query).get().build()))
  }

  override suspend fun listAccessTokens(): AccessTokenListDto =
      decode(execute(request("/v1/tokens").get().build()))

  override suspend fun createAccessToken(name: String): AccessTokenDto {
    val payload = buildJsonObject { put("name", name) }.toString()
    return decode(
        execute(request("/v1/tokens").post(payload.toRequestBody(JSON_MEDIA_TYPE)).build())
    )
  }

  override suspend fun deleteAccessToken(id: String) {
    execute(request("/v1/tokens/${path(id)}").delete().build())
  }

  private fun request(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
    val url = url(path).toHttpUrl().newBuilder()
    for ((name, value) in query) url.addQueryParameter(name, value)
    return Request.Builder()
        .url(url.build())
        .header("Accept", JSON)
        .header("Authorization", bearer(token))
        .header("User-Agent", userAgent)
  }

  /** Runs the request off the main thread and hands a successful response to [read]. */
  private suspend fun <T> call(request: Request, read: (Response) -> T): T =
      withContext(Dispatchers.IO) {
        try {
          client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
              throw failure(response, response.body?.string().orEmpty())
            }
            read(response)
          }
        } catch (e: TaigaException) {
          throw e
        } catch (e: IOException) {
          throw TaigaNetworkException(e)
        }
      }

  private suspend fun execute(request: Request): String =
      call(request) { response -> response.body?.string().orEmpty() }

  private fun failure(response: Response, body: String): TaigaException {
    val error = runCatching { json.decodeFromString<ApiErrorDto>(body) }.getOrNull()
    val status = response.code
    val message = error?.message?.takeIf { it.isNotBlank() } ?: "HTTP $status"
    // PocketBase puts our stable code under data.code next to its own per-field errors.
    val code = (error?.data?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (status) {
      401,
      403 -> TaigaUnauthorizedException(status, message, code)
      409 ->
          TaigaConflictException(
              status,
              message,
              code,
              uploadOffset = response.header(UPLOAD_OFFSET)?.toLongOrNull(),
          )
      else -> TaigaHttpException(status, message, code)
    }
  }

  /** The recording's meta.json as the JSON object the record API stores, never as a string. */
  private fun parseMeta(metaJson: String): JsonElement =
      try {
        json.parseToJsonElement(metaJson)
      } catch (e: Exception) {
        throw TaigaMalformedException(e)
      }

  private inline fun <reified T> decode(body: String): T =
      try {
        json.decodeFromString<T>(body)
      } catch (e: Exception) {
        if (e is TaigaException) throw e
        if (e is SerializationException || e is IllegalArgumentException) {
          throw TaigaMalformedException(e)
        }
        throw e
      }

  private fun url(path: String): String = baseUrl.trimEnd('/') + path

  private fun recordingPath(id: String): String = "$RECORDINGS/${path(id)}"

  private fun analysisPath(id: String): String = "$ANALYSES/${path(id)}"

  /** Ids and file names are restricted by the server, but encode path separators anyway. */
  private fun path(value: String): String =
      value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

  private companion object {
    const val JSON = "application/json"
    const val UPLOAD_OFFSET = "Upload-Offset"
    // PocketBase's record API for the two collections the app reads and writes.
    const val RECORDINGS = "/api/collections/recordings/records"
    const val ANALYSES = "/api/collections/analyses/records"
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    val OCTET = "application/octet-stream".toMediaType()
  }
}

/** The error body of every route: PocketBase's shape around the API's stable code in [data]. */
@Serializable
private data class ApiErrorDto(
    val status: Int = 0,
    val message: String = "",
    val data: JsonObject? = null,
)

/** What the server holds of one file: its stored size, and its encoding (`gzip` or empty). */
data class RemoteFile(val size: Long, val encoding: String)

@Serializable
data class RecordingDto(
    val id: String = "",
    val state: String = "",
    val device: String = "",
    val started_at: String = "",
    val ended_at: String? = null,
    val duration_s: Double = 0.0,
    /** All files have been uploaded and the recording is sealed. */
    val complete: Boolean = false,
    /** The bytes the server stores for the recording, compressed files included. */
    val stored_bytes: Long = 0,
    val created: String = "",
    val updated: String = "",
)

/** One page of PocketBase's record list. */
@Serializable
data class RecordingListDto(
    val page: Int = 1,
    val perPage: Int = 0,
    val totalItems: Long = 0,
    val totalPages: Int = 0,
    val items: List<RecordingDto> = emptyList(),
)

@Serializable
data class RecordingFileDto(
    val name: String = "",
    /** Stored bytes, which are compressed bytes for a gzip file. */
    val size: Long = 0,
    val encoding: String = "",
    val uncompressed_size: Long = 0,
)

@Serializable data class RecordingFileListDto(val items: List<RecordingFileDto> = emptyList())

@Serializable
data class StageSegmentDto(val stage: String = "unknown", val count: Int = 0)

@Serializable
data class StageSummaryDto(
    val epoch_seconds: Int = 30,
    val start_unix_ms: Long = 0,
    val count: Int = 0,
    val segments: List<StageSegmentDto> = emptyList(),
)

@Serializable
data class StageTimelineDto(
    val epoch_seconds: Int = 30,
    val start_unix_ms: Long = 0,
    val count: Int = 0,
    val segments: List<StageSegmentDto> = emptyList(),
    val confidence: JsonElement? = null,
    val artifacts: JsonElement? = null,
)

/**
 * The compact, epoch-aligned heart-rate curve. A null mean marks an epoch without valid
 * samples; it stays a gap in the chart instead of being interpolated.
 */
@Serializable
data class HrSeriesDto(
    val epoch_seconds: Int = 30,
    val start_unix_ms: Long = 0,
    val count: Int = 0,
    val mean_bpm: List<Double?> = emptyList(),
    val sample_count: List<Int>? = null,
)

@Serializable
data class AnalysisSummaryScoreDto(val id: String = "", val value: Double? = null)

@Serializable
data class AnalysisSummaryDto(
    val stages: StageSummaryDto? = null,
    val scores: List<AnalysisSummaryScoreDto> = emptyList(),
)

/** The worker's output, as documented in docs/analysis-contract.md. */
/** One component of a score: its share of the total and its own 0..100 value. */
@Serializable
data class ResultScoreComponentDto(
    val id: String = "",
    val label: String = "",
    val weight: Double = 0.0,
    val value: Double = 0.0,
)

/**
 * One typed result item. The worker supplies the label, unit, explanation and sources, so the app
 * renders what is there without a metric catalog. [series] is set for `series`, [components] and
 * [value] for `score`, and [value] alone for `number`.
 */
@Serializable
data class ResultItemDto(
    val kind: String = "",
    val id: String = "",
    val label: String = "",
    val group: String = "",
    val unit: String = "",
    val value: Double? = null,
    val explanation: String = "",
    val sources: List<String> = emptyList(),
    val series: HrSeriesDto? = null,
    val components: List<ResultScoreComponentDto> = emptyList(),
)

@Serializable
data class AnalysisResultDto(
    val contract_version: Int = 0,
    val model: String = "",
    val model_version: String = "",
    val params: JsonObject? = null,
    val params_hash: String = "",
    val stages: StageTimelineDto? = null,
    val items: List<ResultItemDto> = emptyList(),
    val capabilities: JsonObject? = null,
    val provenance: JsonObject? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * One algorithm applied to one recording. [status] is `queued`, `running`, `succeeded` or `failed`.
 * [summary] is the small headline of a succeeded run, [result] its full output; lists leave the
 * result out.
 */
@Serializable
data class AnalysisDto(
    val id: String = "",
    val recording: String = "",
    val algorithm: String = "",
    val version: String = "",
    val params: JsonObject? = null,
    val status: String = "",
    val error: String = "",
    val attempts: Int = 0,
    val created: String = "",
    val updated: String = "",
    val finished_at: String? = null,
    val summary: AnalysisSummaryDto? = null,
    val result: AnalysisResultDto? = null,
) {
  val isSucceeded: Boolean
    get() = status == "succeeded"

  val isFailed: Boolean
    get() = status == "failed"
}

/** One page of PocketBase's record list. */
@Serializable
data class AnalysisListDto(
    val page: Int = 1,
    val perPage: Int = 0,
    val totalItems: Long = 0,
    val totalPages: Int = 0,
    val items: List<AnalysisDto> = emptyList(),
)

/** One recording of the History list with its analyses; this is what is cached for offline use. */
@Serializable
data class RecordingSummaryDto(
    val recording: RecordingDto = RecordingDto(),
    val analyses: List<AnalysisDto> = emptyList(),
)

@Serializable
data class RecordingSummaryListDto(val items: List<RecordingSummaryDto> = emptyList())

@Serializable
data class AlgorithmNeedDto(
    val name: String = "",
    val required: Boolean = false,
    val min_rate_hz: Double? = null,
    val max_rate_hz: Double? = null,
)

@Serializable
data class AlgorithmDto(
    val id: String = "",
    val version: String = "",
    val name: String = "",
    val description: String = "",
    val license: String = "",
    val needs: List<AlgorithmNeedDto> = emptyList(),
    val epoch_seconds: Int = 30,
    val supported_stages: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class AlgorithmListDto(val items: List<AlgorithmDto> = emptyList())

/**
 * A read-only access token. [token] is set only in the answer that creates it: the server stores a
 * hash, so a list shows the name, the [hint] and when it was made and nothing more.
 */
@Serializable
data class AccessTokenDto(
    val id: String = "",
    val name: String = "",
    /** The last characters of the token, to tell two tokens apart. */
    val hint: String = "",
    val created: String = "",
    val token: String? = null,
)

@Serializable data class AccessTokenListDto(val items: List<AccessTokenDto> = emptyList())
