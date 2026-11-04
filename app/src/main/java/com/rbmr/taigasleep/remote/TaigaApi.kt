package com.rbmr.taigasleep.remote

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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The recording, analysis and history calls from the backend README. The interface is separate so
 * the upload state machine can be tested without Android or a live server.
 */
interface TaigaClient {
  suspend fun createSession(metaJson: String): SessionUploadResponse

  suspend fun getUpload(sessionId: String): SessionUploadResponse

  suspend fun appendFile(
      sessionId: String,
      file: String,
      offset: Long,
      bytes: ByteArray,
      encoding: String? = null,
  ): AppendResponse

  suspend fun completeUpload(sessionId: String): SessionUploadResponse

  suspend fun requestAnalysis(
      sessionId: String,
      model: String,
      version: String,
  ): AnalysisRequestResponse

  suspend fun getAlgorithms(): AlgorithmListDto

  suspend fun deleteSession(sessionId: String)

  suspend fun getAnalysis(analysisId: String): AnalysisDto

  suspend fun getSession(sessionId: String): SessionDetailDto

  /** Downloads one raw recording file, inflating the server's compressed copy when needed. */
  suspend fun downloadSessionFile(sessionId: String, file: String, target: File)

  suspend fun listSessions(perPage: Int = 100): SessionListDto

  suspend fun retryJob(jobId: String): RetryJobResponse
}

open class TaigaException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TaigaNetworkException(cause: Throwable) : TaigaException("network unavailable", cause)

class TaigaMalformedException(cause: Throwable) : TaigaException("malformed response", cause)

open class TaigaHttpException(val status: Int, message: String) : TaigaException(message)

class TaigaUnauthorizedException(status: Int, message: String) : TaigaHttpException(status, message)

/** The append offset no longer matches the server. The caller can re-read the manifest and resume. */
class TaigaConflictException(status: Int, message: String) : TaigaHttpException(status, message)

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

  override suspend fun createSession(metaJson: String): SessionUploadResponse {
    val meta = json.parseToJsonElement(metaJson)
    val payload = buildJsonObject { put("meta", meta) }.toString()
    return decode(execute(post("/api/taiga/sessions", payload)))
  }

  override suspend fun getUpload(sessionId: String): SessionUploadResponse =
      decode(execute(get("/api/taiga/sessions/${path(sessionId)}/upload")))

  override suspend fun appendFile(
      sessionId: String,
      file: String,
      offset: Long,
      bytes: ByteArray,
      encoding: String?,
  ): AppendResponse {
    val query = buildString {
      append("offset=$offset")
      if (!encoding.isNullOrBlank()) append("&encoding=$encoding")
    }
    val request =
        Request.Builder()
            .url(url("/api/taiga/sessions/${path(sessionId)}/upload/file/${path(file)}?$query"))
            .put(bytes.toRequestBody(OCTET))
            .header("Accept", JSON)
            .header("Authorization", token)
            .header("User-Agent", userAgent)
            .build()
    return decode(execute(request))
  }

  override suspend fun completeUpload(sessionId: String): SessionUploadResponse =
      decode(execute(post("/api/taiga/sessions/${path(sessionId)}/upload/complete", "{}")))

  override suspend fun requestAnalysis(
      sessionId: String,
      model: String,
      version: String,
  ): AnalysisRequestResponse {
    val payload =
        buildJsonObject {
              put(
                  "models",
                  buildJsonArray {
                    add(
                        buildJsonObject {
                          put("model", model)
                          put("version", version)
                          put("params", buildJsonObject {})
                        }
                    )
                  },
              )
            }
            .toString()
    return decode(execute(post("/api/taiga/sessions/${path(sessionId)}/analysis", payload)))
  }

  override suspend fun getAlgorithms(): AlgorithmListDto =
      decode(execute(get("/api/taiga/algorithms")))

  override suspend fun deleteSession(sessionId: String) {
    execute(delete("/api/collections/sessions/records/${path(sessionId)}"))
  }

  override suspend fun getAnalysis(analysisId: String): AnalysisDto =
      decode(execute(get("/api/taiga/analyses/${path(analysisId)}")))

  override suspend fun getSession(sessionId: String): SessionDetailDto =
      decode(execute(get("/api/taiga/sessions/${path(sessionId)}")))

  override suspend fun downloadSessionFile(sessionId: String, file: String, target: File) {
    withContext(Dispatchers.IO) {
      val request = get("/api/taiga/sessions/${path(sessionId)}/files/${path(file)}")
      try {
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw failure(response.code, response.body?.string().orEmpty())
          }
          val body = response.body
          if (body == null) {
            throw TaigaMalformedException(IllegalStateException("empty response body"))
          }
          target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
      } catch (e: TaigaException) {
        target.delete()
        throw e
      } catch (e: IOException) {
        target.delete()
        throw TaigaNetworkException(e)
      }
    }
  }

  override suspend fun listSessions(perPage: Int): SessionListDto =
      decode(execute(get("/api/taiga/sessions?perPage=${perPage.coerceIn(1, 100)}&sort=-started_at")))

  override suspend fun retryJob(jobId: String): RetryJobResponse =
      decode(execute(post("/api/taiga/jobs/${path(jobId)}/retry", "{}")))

  private fun get(path: String): Request =
      Request.Builder()
          .url(url(path))
          .get()
          .header("Accept", JSON)
          .header("Authorization", token)
          .header("User-Agent", userAgent)
          .build()

  private fun post(path: String, payload: String): Request =
      Request.Builder()
          .url(url(path))
          .post(payload.toRequestBody(JSON_MEDIA_TYPE))
          .header("Accept", JSON)
          .header("Authorization", token)
          .header("User-Agent", userAgent)
          .build()

  private fun delete(path: String): Request =
      Request.Builder()
          .url(url(path))
          .delete()
          .header("Accept", JSON)
          .header("Authorization", token)
          .header("User-Agent", userAgent)
          .build()

  private suspend fun execute(request: Request): String =
      withContext(Dispatchers.IO) {
        try {
          client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw failure(response.code, body)
            body
          }
        } catch (e: TaigaException) {
          throw e
        } catch (e: IOException) {
          throw TaigaNetworkException(e)
        }
      }

  private fun failure(status: Int, body: String): TaigaException {
    val message =
        runCatching {
              json.decodeFromString<ApiErrorDto>(body).message
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "HTTP $status"
    return when {
      status == 401 || status == 403 -> TaigaUnauthorizedException(status, message)
      status == 409 -> TaigaConflictException(status, message)
      else -> TaigaHttpException(status, message)
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

  /** Ids and file names are server-generated and already restricted, but encode path separators. */
  private fun path(value: String): String =
      value.replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

  private companion object {
    const val JSON = "application/json"
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

@Serializable private data class ApiErrorDto(val message: String = "")

@Serializable
data class SessionDto(
    val id: String = "",
    val client_session_id: String = "",
    val device: String = "",
    val started_at: String = "",
    val ended_at: String = "",
    val duration_s: Double = 0.0,
    val state: String = "",
    val upload_status: String = "",
    val uploaded_at: String = "",
    val upload_error: String = "",
    val created: String = "",
    val updated: String = "",
)

@Serializable
data class UploadFileDto(
    val length: Long = 0,
    val validated_length: Long = 0,
    val uncompressed_length: Long = 0,
    val members: Int = 0,
    val frames: Int = 0,
    val lines: Int = 0,
    val state: String = "",
    val error: String = "",
    val truncated: Boolean = false,
    val corrupt_at: Long? = null,
    val pending_bytes: Long? = null,
    val encoding: String = "",
    val stored_name: String = "",
    val compressed: Boolean = false,
    val updated: String = "",
)

@Serializable
data class UploadLimitsDto(
    val max_total_bytes: Long = 0,
    val max_file_bytes: Long = 0,
    val max_uncompressed_total_bytes: Long = 0,
    val max_uncompressed_file_bytes: Long = 0,
    val max_chunk_bytes: Long = 0,
    val max_uncompressed_chunk_bytes: Long = 0,
    val max_expansion_ratio: Double = 0.0,
    val max_members: Int = 0,
)

@Serializable
data class CompressionInfoDto(
    val codec: String = "",
    val version: Int = 0,
    val member_per_chunk: Boolean = false,
    val deterministic: Boolean = false,
)

@Serializable
data class UploadManifestDto(
    val session: String = "",
    val upload_status: String = "",
    val uploaded_at: String = "",
    val upload_error: String = "",
    val completed: Boolean = false,
    val completed_at: String = "",
    val encoding: String = "",
    val accepted_encodings: List<String> = emptyList(),
    val compression: CompressionInfoDto = CompressionInfoDto(),
    val total_bytes: Long = 0,
    val uncompressed_bytes: Long = 0,
    val limits: UploadLimitsDto = UploadLimitsDto(),
    val files: Map<String, UploadFileDto> = emptyMap(),
)

@Serializable
data class SessionUploadResponse(
    val session: SessionDto = SessionDto(),
    val upload: UploadManifestDto = UploadManifestDto(),
)

@Serializable
data class AppendResponse(
    val file: String = "",
    val offset: Long = 0,
    val written: Int = 0,
    val length: Long = 0,
    val validated_length: Long = 0,
    val uncompressed_length: Long = 0,
    val members: Int = 0,
    val encoding: String = "",
    val frames: Int = 0,
    val lines: Int = 0,
    val state: String = "",
    val error: String = "",
    val truncated: Boolean = false,
)

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

@Serializable
data class QualityComponentDto(
    val value: Double? = null,
    val weight: Double = 0.0,
    val available: Boolean = false,
)

@Serializable
data class QualityDto(
    val score: Double? = null,
    val weights_version: Int? = null,
    val components: Map<String, QualityComponentDto> = emptyMap(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class SleepQualityDto(
    val score: Double? = null,
    val components_version: Int? = null,
    val components: Map<String, QualityComponentDto> = emptyMap(),
    val notes: List<String> = emptyList(),
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
data class MetricSupportDto(val status: String = "", val reason: String? = null)

@Serializable
data class AnalysisCountsDto(
    val total: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
)

@Serializable
data class AnalysisDto(
    val id: String = "",
    val session: String = "",
    val model: String = "",
    val model_version: String = "",
    val params_hash: String = "",
    val status: String = "",
    val contract_version: Int = 0,
    val stages: StageTimelineDto? = null,
    val hr: HrSeriesDto? = null,
    val metrics: JsonObject? = null,
    val metric_support: Map<String, MetricSupportDto>? = null,
    val quality: QualityDto? = null,
    val sleep_quality: SleepQualityDto? = null,
    val capabilities: JsonObject? = null,
    val provenance: JsonObject? = null,
    val extras: JsonObject? = null,
    val warnings: List<String> = emptyList(),
    val error: String = "",
    val created: String = "",
    val updated: String = "",
)

@Serializable
data class AnalysisSummaryDto(
    val id: String = "",
    val session: String = "",
    val model: String = "",
    val model_version: String = "",
    val params_hash: String = "",
    val status: String = "",
    val error: String = "",
    val metrics: JsonObject? = null,
    val quality: QualityDto? = null,
    val sleep_quality: SleepQualityDto? = null,
    val stages: StageSummaryDto? = null,
    val created: String = "",
    val updated: String = "",
)

@Serializable
data class JobDto(
    val id: String = "",
    val session: String = "",
    val analysis: String = "",
    val status: String = "",
    val error: String = "",
    val created: String = "",
    val updated: String = "",
)

@Serializable
data class SessionSummaryDto(
    val session: SessionDto = SessionDto(),
    val analyses: List<AnalysisSummaryDto> = emptyList(),
    val analysis_summary: AnalysisCountsDto = AnalysisCountsDto(),
)

@Serializable
data class SessionListDto(
    val page: Int = 1,
    val perPage: Int = 0,
    val hasMore: Boolean = false,
    val items: List<SessionSummaryDto> = emptyList(),
)

@Serializable
data class SessionDetailDto(
    val session: SessionDto = SessionDto(),
    val upload: UploadManifestDto = UploadManifestDto(),
    val analyses: List<AnalysisDto> = emptyList(),
    val jobs: List<JobDto> = emptyList(),
    val analysis_summary: AnalysisCountsDto = AnalysisCountsDto(),
)

@Serializable
data class AnalysisRequestResponse(
    val analyses: List<AnalysisDto> = emptyList(),
    val jobs: List<JobDto> = emptyList(),
)

@Serializable
data class RetryJobResponse(val job: JobDto = JobDto(), val analysis: AnalysisDto? = null)

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
data class AlgorithmListDto(val algorithms: List<AlgorithmDto> = emptyList())
