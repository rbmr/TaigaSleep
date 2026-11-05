package com.rbmr.taigasleep.backend

import com.rbmr.taigasleep.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The recording, analysis and algorithm calls of docs/api.md. The interface is separate so the
 * upload state machine can be tested without Android or a live server.
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
      limit: Int = 200,
      offset: Int = 0,
  ): AnalysisListDto
}

/**
 * The newest [limit] recordings with their analyses, which is what History shows. The analyses
 * come from the account's paged analysis list instead of one request per recording.
 */
suspend fun TaigaClient.recordingSummaries(limit: Int = 100): RecordingSummaryListDto {
  val recordings = listRecordings(limit).items
  val wanted = recordings.map { it.id }.toSet()
  val byRecording = mutableMapOf<String, MutableList<AnalysisDto>>()
  var offset = 0
  while (offset < MAX_LISTED_ANALYSES) {
    val page = listAnalyses(offset = offset)
    for (analysis in page.items) {
      if (analysis.recording !in wanted) continue
      byRecording.getOrPut(analysis.recording) { mutableListOf() }.add(analysis)
    }
    offset += page.items.size
    if (page.items.isEmpty() || offset >= page.total) break
  }
  return RecordingSummaryListDto(
      recordings.map { RecordingSummaryDto(it, byRecording[it.id].orEmpty()) }
  )
}

private const val MAX_LISTED_ANALYSES = 2000

/** The error `code` values of docs/api.md that the app acts on. */
object ApiCode {
  const val OFFSET_MISMATCH = "offset_mismatch"
  const val RECORDING_COMPLETE = "recording_complete"
  const val RECORDING_IN_PROGRESS = "recording_in_progress"
  const val CHUNK_TOO_LARGE = "chunk_too_large"
}

open class TaigaException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TaigaNetworkException(cause: Throwable) : TaigaException("network unavailable", cause)

class TaigaMalformedException(cause: Throwable) : TaigaException("malformed response", cause)

/** An error answer. [code] is the stable identifier from the `{code, message}` body, or empty. */
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
    private val client: OkHttpClient = defaultTaigaClient(),
    private val userAgent: String = "taiga-sleep-android/${BuildConfig.VERSION_NAME}",
) : TaigaClient {
  private val json =
      Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
      }

  override suspend fun putRecording(id: String, metaJson: String): RecordingDto =
      decode(
          execute(
              request("/v1/recordings/${path(id)}")
                  .put(metaJson.toRequestBody(JSON_MEDIA_TYPE))
                  .build()
          )
      )

  override suspend fun getRecording(id: String): RecordingDto =
      decode(execute(request("/v1/recordings/${path(id)}").get().build()))

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
              request("/v1/recordings/${path(id)}")
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
              put("algorithm", algorithm)
              if (version.isNotBlank()) put("version", version)
              if (params != null) put("params", params)
            }
            .toString()
    return decode(
        execute(
            request("/v1/recordings/${path(recordingId)}/analyses")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )
    )
  }

  override suspend fun getAlgorithms(): AlgorithmListDto =
      decode(execute(request("/v1/algorithms").get().build()))

  override suspend fun deleteRecording(id: String) {
    execute(request("/v1/recordings/${path(id)}").delete().build())
  }

  override suspend fun getAnalysis(analysisId: String): AnalysisDto =
      decode(execute(request("/v1/analyses/${path(analysisId)}").get().build()))

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
    val query = "limit=${limit.coerceIn(1, 200)}&sort=-started_at"
    return decode(execute(request("/v1/recordings?$query").get().build()))
  }

  override suspend fun listAnalyses(recording: String?, limit: Int, offset: Int): AnalysisListDto {
    val base =
        if (recording == null) "/v1/analyses" else "/v1/recordings/${path(recording)}/analyses"
    val query = "limit=${limit.coerceIn(1, 200)}&offset=${offset.coerceAtLeast(0)}&sort=-created"
    return decode(execute(request("$base?$query").get().build()))
  }

  private fun request(path: String): Request.Builder =
      Request.Builder()
          .url(url(path))
          .header("Accept", JSON)
          .header("Authorization", bearer(token))
          .header("User-Agent", userAgent)

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
    val code = error?.code.orEmpty()
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

  /** Ids and file names are restricted by the server, but encode path separators anyway. */
  private fun path(value: String): String =
      value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

  private companion object {
    const val JSON = "application/json"
    const val UPLOAD_OFFSET = "Upload-Offset"
    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    val OCTET = "application/octet-stream".toMediaType()
  }
}

private fun defaultTaigaClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

@Serializable private data class ApiErrorDto(val code: String = "", val message: String = "")

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
    val size_bytes: Long = 0,
    val created: String = "",
    val updated: String = "",
)

@Serializable
data class RecordingListDto(
    val items: List<RecordingDto> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Long = 0,
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
    val probabilities: JsonElement? = null,
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
    val extras: JsonObject? = null,
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

@Serializable
data class AnalysisListDto(
    val items: List<AnalysisDto> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Long = 0,
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
    val citation: JsonElement? = null,
    val homepage: JsonElement? = null,
    val needs: List<AlgorithmNeedDto> = emptyList(),
    val epoch_seconds: Int = 30,
    val stage_scheme: String = "",
    val supported_stages: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val default_params: JsonObject? = null,
    val params_schema: JsonObject? = null,
)

@Serializable
data class AlgorithmListDto(val items: List<AlgorithmDto> = emptyList())
