package com.suzuai.luminaris.shared.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ============================================================================
 * Wire models that flow between the two APKs and the FastAPI backend.
 * Keep these stable — every change is a coordinated client/panel/server bump.
 * ============================================================================ */

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
)

@Serializable
data class ReadyResponse(
    val ready: Boolean,
    val missing: List<String> = emptyList(),
)

@Serializable
data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val user: String = "root",
    @SerialName("auth_mode") val authMode: String = "password", // "password" | "key"
    val password: String = "",
    @SerialName("private_key") val privateKey: String = "",
    val workspace: String = "~/suzu_workspace",
)

@Serializable
data class AiProviderConfig(
    val kind: String = "openai_compat", // anthropic | openai | openai_compat
    @SerialName("base_url") val baseUrl: String = "",
    val model: String = "",
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("max_tokens") val maxTokens: Int = 0, // 0 = unlimited
    val temperature: Float = 1.0f,
)

@Serializable
data class AdminConfig(
    val ssh: SshConfig = SshConfig(),
    @SerialName("ai_provider") val aiProvider: AiProviderConfig = AiProviderConfig(),
    @SerialName("max_iterations") val maxIterations: Int = 100,
)

@Serializable
data class VerifyRow(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

@Serializable
data class VerifyResponse(
    val rows: List<VerifyRow>,
)

@Serializable
data class LogEvent(
    val ts: Double,
    val level: String,
    val source: String,
    val message: String,
)

@Serializable
data class ApkArtifact(
    val id: String,
    val name: String,
    val kind: String, // "decompiled" | "recompiled" | "signed"
    @SerialName("session_id") val sessionId: String? = null,
    val size: Long,
    @SerialName("created_at") val createdAt: Double,
)

/* --- Chat / agent loop --- */

@Serializable
data class Session(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAt: Double,
    @SerialName("updated_at") val updatedAt: Double,
    val status: String = "idle", // idle | typing | tool | done | error
    @SerialName("tokens_in") val tokensIn: Int = 0,
    @SerialName("tokens_out") val tokensOut: Int = 0,
    @SerialName("last_event_seq") val lastEventSeq: Int = 0,
)

@Serializable
data class ChatMessage(
    val role: String, // "user" | "assistant" | "tool"
    val content: String,
    @SerialName("tool_call") val toolCall: ToolCall? = null,
    @SerialName("tool_result") val toolResult: ToolResult? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val args: String,
)

@Serializable
data class ToolResult(
    val id: String,
    val output: String,
    @SerialName("is_error") val isError: Boolean = false,
)

@Serializable
data class ChatRequest(
    @SerialName("session_id") val sessionId: String,
    val content: String,
    @SerialName("max_iterations") val maxIterations: Int? = null,
)

/** SSE event envelope. `type` decides which other fields are populated. */
@Serializable
data class StreamEvent(
    val type: String, // delta | tool_call | tool_result | done | error
    val delta: String? = null,
    @SerialName("tool_call") val toolCall: ToolCall? = null,
    @SerialName("tool_result") val toolResult: ToolResult? = null,
    val message: String? = null, // for errors
)

/** Token usage for one LLM turn (and per-session totals). */
@Serializable
data class TokenUsage(
    val prompt: Int = 0,
    val completion: Int = 0,
    val total: Int = 0,
)

/** Persisted agent event — mirrors backend `agent_events` rows. */
@Serializable
data class AgentEvent(
    val seq: Int,
    val ts: Double,
    val type: String, // delta | tool_call | tool_result | usage | status | done | error
    val delta: String? = null,
    @SerialName("tool_call") val toolCall: ToolCall? = null,
    @SerialName("tool_result") val toolResult: ToolResult? = null,
    val usage: TokenUsage? = null,
    val status: String? = null,
    val message: String? = null,
)

/** Long-poll envelope from `GET /v1/sessions/{id}/events`. */
@Serializable
data class EventsResponse(
    @SerialName("session_id") val sessionId: String,
    val status: String,
    @SerialName("tokens_in") val tokensIn: Int,
    @SerialName("tokens_out") val tokensOut: Int,
    val events: List<AgentEvent>,
    @SerialName("last_seq") val lastSeq: Int,
    val running: Boolean,
)
