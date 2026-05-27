package com.suzuai.luminaris.shared.net

import com.suzuai.luminaris.shared.data.AdminConfig
import com.suzuai.luminaris.shared.data.ApkArtifact
import com.suzuai.luminaris.shared.data.ChatMessage
import com.suzuai.luminaris.shared.data.ChatRequest
import com.suzuai.luminaris.shared.data.HealthResponse
import com.suzuai.luminaris.shared.data.LogEvent
import com.suzuai.luminaris.shared.data.ReadyResponse
import com.suzuai.luminaris.shared.data.Session
import com.suzuai.luminaris.shared.data.StreamEvent
import com.suzuai.luminaris.shared.data.VerifyResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin client over the FastAPI backend.
 *
 * Both APKs (panel and client) talk to the same backend via this class — the
 * panel uses the admin endpoints, the client uses sessions + chat.
 *
 * `baseUrl` is whatever the user entered ("https://server:8765"), without a
 * trailing slash. `token` is the SUZU_TOKEN printed by install.sh.
 */
class BackendClient(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient = defaultClient,
) {

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private fun req(path: String): Request.Builder = Request.Builder()
        .url("$baseUrl$path")
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    private inline fun <reified T> get(path: String): T = client.newCall(req(path).get().build()).body<T>()
    private inline fun <reified T, reified B> post(path: String, body: B): T =
        client.newCall(req(path).post(json.encodeToString(body).toRequestBody(JSON)).build()).body<T>()
    private inline fun <reified T, reified B> put(path: String, body: B): T =
        client.newCall(req(path).put(json.encodeToString(body).toRequestBody(JSON)).build()).body<T>()

    private inline fun <reified T> Call.body(): T {
        execute().use { resp ->
            if (!resp.isSuccessful) throw BackendException(resp.code, resp.body?.string().orEmpty())
            val text = resp.body?.string().orEmpty()
            return if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST") Unit as T
            } else {
                json.decodeFromString(text)
            }
        }
    }

    /* ---- public surface ---- */

    suspend fun health(): HealthResponse = withContext(Dispatchers.IO) { get("/v1/health") }
    suspend fun ready(): ReadyResponse = withContext(Dispatchers.IO) { get("/v1/ready") }

    suspend fun getConfig(): AdminConfig = withContext(Dispatchers.IO) { get("/v1/admin/config") }
    suspend fun putConfig(cfg: AdminConfig): AdminConfig = withContext(Dispatchers.IO) { put("/v1/admin/config", cfg) }
    suspend fun verifyConfig(): VerifyResponse = withContext(Dispatchers.IO) { post("/v1/admin/config/verify", Unit) }
    suspend fun wipeConfig(): AdminConfig = withContext(Dispatchers.IO) {
        client.newCall(req("/v1/admin/config").delete().build()).body()
    }

    suspend fun listApks(): List<ApkArtifact> = withContext(Dispatchers.IO) { get("/v1/admin/apks") }

    /** Returns the URL the panel can hand off to a system download / share intent. */
    fun apkDownloadUrl(id: String): String = "$baseUrl/v1/admin/apks/$id/download"

    suspend fun listSessions(): List<Session> = withContext(Dispatchers.IO) { get("/v1/sessions") }
    suspend fun createSession(title: String): Session = withContext(Dispatchers.IO) {
        post("/v1/sessions", mapOf("title" to title))
    }
    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        client.newCall(req("/v1/sessions/$id").delete().build()).body<Unit>()
    }
    suspend fun getMessages(id: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        get("/v1/sessions/$id/messages")
    }

    /** Live log SSE for the panel. */
    fun streamLogs(): Flow<LogEvent> = sseFlow("/v1/admin/logs/stream") { json.decodeFromString<LogEvent>(it) }

    /** Streaming chat for the client. */
    fun streamChat(request: ChatRequest): Flow<StreamEvent> = flow {
        val body = json.encodeToString(request).toRequestBody(JSON)
        val sseReq = req("/v1/chat").post(body).header("Accept", "text/event-stream").build()
        // We hand-roll SSE here because okhttp's EventSources requires GET. The
        // server streams `data: <json>\n\n` frames; we yield each parsed event.
        client.newCall(sseReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw BackendException(resp.code, resp.body?.string().orEmpty())
            }
            val source = resp.body?.source() ?: return@use
            val buffer = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    val payload = buffer.toString().trim()
                    buffer.clear()
                    if (payload.isNotEmpty()) {
                        runCatching { json.decodeFromString<StreamEvent>(payload) }
                            .onSuccess { emit(it) }
                    }
                } else if (line.startsWith("data:")) {
                    buffer.appendLine(line.removePrefix("data:").trim())
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private inline fun <reified T> sseFlow(
        path: String,
        crossinline parse: (String) -> T,
    ): Flow<T> = callbackFlow {
        val request = req(path).build()
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { parse(data) }
                    .onSuccess { trySend(it) }
            }

            override fun onClosed(eventSource: EventSource) { close() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("SSE failed: ${response?.code}"))
            }
        }
        val es = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { es.cancel() }
    }.flowOn(Dispatchers.IO)
}

class BackendException(val httpCode: Int, val responseBody: String) :
    IOException("HTTP $httpCode: ${responseBody.take(300)}")
