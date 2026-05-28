package com.suzuai.luminaris.app.ui.ask

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.suzuai.luminaris.app.ClientApp
import com.suzuai.luminaris.app.ui.sessions.ActiveSession
import com.suzuai.luminaris.app.ui.sessions.AgentState
import com.suzuai.luminaris.app.ui.setup.clientFieldColors
import com.suzuai.luminaris.shared.data.AgentEvent
import com.suzuai.luminaris.shared.data.Attachment
import com.suzuai.luminaris.shared.data.ChatMessage
import com.suzuai.luminaris.shared.data.ChatRequest
import com.suzuai.luminaris.shared.data.ToolResult
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chat surface with background-task polling, animations, and live token usage.
 *
 * Architecture:
 *  * POST /v1/chat kicks off the agent loop server-side (returns immediately).
 *  * A polling loop hits GET /v1/sessions/{id}/events?since=<seq> every ~700ms.
 *  * Even if this APK is backgrounded/killed, the agent keeps running. On reopen,
 *    polling resumes from the last known seq — no events lost.
 */
@Composable
fun AskScreen() {
    val app = ClientApp.instance
    val scope = rememberCoroutineScope()
    val (url, _token) = app.store.pair.collectAsState(initial = "" to "").value
    val client = remember(url) { BackendClient(url, "") }

    val sessionId = ActiveSession.id
    val sessionTitle = ActiveSession.title

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var assistantBuffer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var agentStatus by remember { mutableStateOf("idle") }
    var tokensIn by remember { mutableIntStateOf(0) }
    var tokensOut by remember { mutableIntStateOf(0) }
    var lastSeq by remember { mutableIntStateOf(0) }
    var polling by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Load history on session open.
    LaunchedEffect(sessionId, url) {
        messages.clear()
        assistantBuffer = ""
        error = null
        lastSeq = 0
        tokensIn = 0
        tokensOut = 0
        agentStatus = "idle"
        sending = false
        if (sessionId == null || url.isBlank()) return@LaunchedEffect
        runCatching { client.getMessages(sessionId) }
            .onSuccess { messages.addAll(it) }
            .onFailure { error = "Load failed: ${it.message}" }
        // Catch up to latest events seq.
        runCatching { client.pollEvents(sessionId, 0, 1) }
            .onSuccess {
                lastSeq = it.lastSeq
                agentStatus = it.status
                tokensIn = it.tokensIn
                tokensOut = it.tokensOut
                if (it.running) {
                    polling = true
                    // Mirror server-side state so the Send button stays
                    // disabled when we re-open the app mid-turn. Otherwise
                    // the user can tap Send and hit HTTP 409.
                    sending = true
                }
            }
    }

    // Polling coroutine: drains events as they appear.
    LaunchedEffect(polling, sessionId, url) {
        if (!polling || sessionId == null || url.isBlank()) return@LaunchedEffect
        try {
            while (isActive && polling) {
                val resp = try {
                    client.pollEvents(sessionId, lastSeq)
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    delay(2000)
                    continue
                }
                agentStatus = resp.status
                tokensIn = resp.tokensIn
                tokensOut = resp.tokensOut
                AgentState.status = resp.status
                AgentState.tokensIn = resp.tokensIn
                AgentState.tokensOut = resp.tokensOut
                // Keep `sending` synced with the server-truth `running`
                // flag so the Send button reflects reality even if the
                // backend started/finished a turn from another client.
                if (resp.running && !sending) {
                    sending = true
                }
                for (ev in resp.events) {
                    applyEvent(ev, messages,
                        appendDelta = { assistantBuffer += it },
                        flushAssistant = {
                            if (assistantBuffer.isNotEmpty()) {
                                messages.add(ChatMessage(role = "assistant", content = assistantBuffer))
                                assistantBuffer = ""
                            }
                        },
                    )
                }
                lastSeq = resp.lastSeq
                if (!resp.running) {
                    if (assistantBuffer.isNotEmpty()) {
                        messages.add(ChatMessage(role = "assistant", content = assistantBuffer))
                        assistantBuffer = ""
                    }
                    polling = false
                    sending = false
                }
                delay(700)
            }
        } catch (_: CancellationException) { /* normal */ }
    }

    // Auto-scroll on new content.
    LaunchedEffect(messages.size, assistantBuffer) {
        val target = (messages.size - 1).coerceAtLeast(0)
        runCatching { listState.animateScrollToItem(target) }
    }

    Column(Modifier.fillMaxSize().background(LuminarisColors.Bg)) {
        if (sessionId == null) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    "Pick or create a session in the Sessions tab.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        // ===== Title + status pill + token counter =====
        Row(
            Modifier
                .fillMaxWidth()
                .background(LuminarisColors.Surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                sessionTitle ?: "Session",
                color = LuminarisColors.AccentCyan,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            StatusPillAnimated(agentStatus)
            Spacer(Modifier.width(10.dp))
            TokenCounter(tokensIn, tokensOut)
        }

        // ===== Messages =====
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            itemsIndexed(messages) { idx, m ->
                val isLastToolCall = (idx == messages.lastIndex) && m.toolCall != null && m.toolResult == null
                val live = isLastToolCall && agentStatus == "tool"
                AnimatedBubble(idx) { MessageView(m, isLiveCall = live) }
            }
            if (assistantBuffer.isNotEmpty()) {
                item {
                    AnimatedBubble(messages.size) {
                        AssistantBubble(assistantBuffer, typing = true)
                    }
                }
            }
            if (error != null) {
                item {
                    Text(error!!, color = LuminarisColors.AccentRed, modifier = Modifier.padding(8.dp))
                }
            }
        }

        val pendingAttachments = remember { mutableStateListOf<Attachment>() }
        var uploading by remember { mutableStateOf(false) }
        val ctx = LocalContext.current
        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            uploading = true
            scope.launch {
                runCatching {
                    val cr = ctx.contentResolver
                    val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    val name = displayNameFromUri(ctx, uri) ?: "file"
                    val mime = cr.getType(uri) ?: "application/octet-stream"
                    client.uploadAttachment(sessionId, bytes, name, mime)
                }.onSuccess { pendingAttachments.add(it) }
                    .onFailure { error = "Upload failed: ${it.message}" }
                uploading = false
            }
        }

        // The composer is "busy" whenever the agent task is in-flight or
        // about to be — we treat the local `sending` flag, an active poll
        // loop, and any non-idle server status as equivalent for the
        // purposes of locking the Send button. This is what prevents the
        // HTTP 409 "session already has a running agent" race after the
        // app is backgrounded and re-opened mid-turn.
        val isBusy = sending ||
            polling ||
            agentStatus == "typing" ||
            agentStatus == "tool"

        if (isBusy) {
            StopBar(
                onStop = {
                    scope.launch {
                        runCatching { client.stopSession(sessionId) }
                            .onSuccess {
                                polling = false
                                sending = false
                                agentStatus = "idle"
                                AgentState.status = "idle"
                            }
                            .onFailure { error = "Stop failed: ${it.message}" }
                    }
                },
            )
        }

        if (agentStatus == "error" && !isBusy) {
            ResumeBar(
                onResume = {
                    error = null
                    sending = true
                    scope.launch {
                        runCatching {
                            client.resumeSession(sessionId)
                            polling = true
                        }.onFailure {
                            error = "Resume failed: ${it.message}"
                            sending = false
                        }
                    }
                },
            )
        }

        Composer(
            value = input,
            onChange = { input = it },
            sending = isBusy,
            attachments = pendingAttachments,
            uploading = uploading,
            onPick = { pickFile.launch("*/*") },
            onRemoveAttachment = { pendingAttachments.remove(it) },
            onSend = {
                val text = input.trim()
                if ((text.isEmpty() && pendingAttachments.isEmpty()) || isBusy) return@Composer
                val attIds = pendingAttachments.map { it.id }
                val attNames = pendingAttachments.joinToString(", ") { it.name }
                val displayText = if (text.isNotEmpty()) text else "(attached: $attNames)"
                input = ""
                error = null
                messages.add(ChatMessage(role = "user", content = displayText))
                sending = true
                assistantBuffer = ""
                pendingAttachments.clear()
                scope.launch {
                    runCatching {
                        client.startChat(
                            ChatRequest(
                                sessionId = sessionId,
                                content = if (text.isNotEmpty()) text else "Please analyse the attached file(s).",
                                attachmentIds = attIds,
                            ),
                        )
                        polling = true
                    }.onFailure {
                        error = "Send failed: ${it.message}"
                        sending = false
                    }
                }
            },
        )
    }
}

private fun displayNameFromUri(ctx: android.content.Context, uri: android.net.Uri): String? {
    var result: String? = null
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) result = c.getString(idx)
        }
    }
    if (result == null) result = uri.lastPathSegment
    return result
}

// ========================================================================
// Events → messages
// ========================================================================

private fun applyEvent(
    ev: AgentEvent,
    messages: MutableList<ChatMessage>,
    appendDelta: (String) -> Unit,
    flushAssistant: () -> Unit,
) {
    when (ev.type) {
        "delta" -> ev.delta?.let { appendDelta(it) }
        "tool_call" -> {
            flushAssistant()
            val tc = ev.toolCall ?: return
            messages.add(ChatMessage(role = "tool", content = "→ ${tc.name}(${tc.args.take(120)})", toolCall = tc))
        }
        "tool_result" -> {
            val tr = ev.toolResult ?: return
            val prefix = if (tr.isError) "✗" else "✓"
            messages.add(ChatMessage(role = "tool", content = "$prefix exit=${if (tr.isError) 1 else 0}\n${tr.output.take(400)}", toolResult = tr))
        }
        "error" -> {
            flushAssistant()
            messages.add(ChatMessage(role = "assistant", content = "Error: ${ev.message.orEmpty()}"))
        }
        "done" -> flushAssistant()
        else -> Unit
    }
}

// ========================================================================
// UI Components with animations
// ========================================================================

/** Animated three-dot status: Typing… / Running tool… / Idle */
@Composable
private fun StatusPillAnimated(status: String) {
    val transition = rememberInfiniteTransition(label = "dots")
    val dotAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val (label, color) = when (status) {
        "typing" -> "Typing" to LuminarisColors.AccentCyan
        "tool" -> "Running tool" to LuminarisColors.AccentAmber
        "error" -> "Error" to LuminarisColors.AccentRed
        else -> "Idle" to LuminarisColors.Muted
    }
    val isActive = status == "typing" || status == "tool"
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isActive) {
            Box(
                Modifier
                    .size(6.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (isActive) "$label…" else label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Live token counter: ↑in · ↓out */
@Composable
private fun TokenCounter(tokensIn: Int, tokensOut: Int) {
    if (tokensIn == 0 && tokensOut == 0) return
    Text(
        "↑${fmtTokens(tokensIn)} ↓${fmtTokens(tokensOut)}",
        color = LuminarisColors.TextDim,
        style = MaterialTheme.typography.labelSmall,
    )
}

private fun fmtTokens(n: Int): String = when {
    n < 1000 -> "$n"
    n < 100_000 -> "${n / 1000}.${(n % 1000) / 100}k"
    else -> "${n / 1000}k"
}

/** Fade-in + slide-up entrance for each message bubble. */
@Composable
private fun AnimatedBubble(index: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
    ) {
        content()
    }
}

/** Assistant text bubble with optional caret blink. */
@Composable
private fun AssistantBubble(text: String, typing: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "caretAlpha",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(LuminarisColors.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row {
                Text(text, color = LuminarisColors.Text)
                if (typing) {
                    Text("▌", color = LuminarisColors.AccentCyan.copy(alpha = caretAlpha))
                }
            }
        }
    }
}

@Composable
private fun MessageView(m: ChatMessage, isLiveCall: Boolean = false) {
    when (m.role) {
        "user" -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(LuminarisColors.UserBubble)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(m.content, color = LuminarisColors.Text)
                }
            }
        }
        "assistant" -> AssistantBubble(m.content, typing = false)
        else -> ToolBubbleView(m, isLiveCall = isLiveCall)
    }
}

/** Tool call / result card with icon, status, and expand/collapse body.
 *
 *  Each card represents a single ChatMessage that carries either a toolCall
 *  (the request) or a toolResult (the response). The user can tap the header
 *  to expand the full args / output — by default we show only a short summary
 *  to keep the chat scrollable.
 */
@Composable
private fun ToolBubbleView(m: ChatMessage, isLiveCall: Boolean) {
    val tc = m.toolCall
    val tr = m.toolResult

    val name = tc?.name ?: "tool"
    val rawBody = tc?.args ?: tr?.output.orEmpty()
    val isError = tr?.isError == true
    val isResult = tr != null

    var expanded by remember(m) { mutableStateOf(false) }

    val accent = when {
        isError -> LuminarisColors.AccentRed
        isResult -> LuminarisColors.AccentGreen
        isLiveCall -> LuminarisColors.AccentAmber
        else -> LuminarisColors.AccentCyan
    }

    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(LuminarisColors.ToolBubble)
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = if (expanded) 0.45f else 0.18f),
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isResult) {
                    Icon(
                        if (isError) Icons.Outlined.Close else Icons.Outlined.Check,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                } else if (isLiveCall) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = accent,
                    )
                } else {
                    Icon(
                        toolIcon(name),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isResult) toolResultSummary(name, tr) else "$name  \u00b7  ${shortArgs(tc?.args)}",
                    color = LuminarisColors.Text,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontFamily = if (isResult) FontFamily.Monospace else FontFamily.Default,
                    ),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForwardIos,
                    contentDescription = null,
                    tint = LuminarisColors.Muted,
                    modifier = Modifier
                        .size(12.dp)
                        .padding(start = 4.dp),
                )
            }
            AnimatedContent(
                targetState = expanded,
                transitionSpec = { expandVertically() togetherWith shrinkVertically() },
                label = "toolBody",
            ) { isOpen ->
                if (isOpen) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            rawBody.ifBlank { "(empty)" },
                            color = LuminarisColors.TextDim,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                } else {
                    Spacer(Modifier.height(0.dp))
                }
            }
        }
    }
}

private fun toolIcon(name: String) = when {
    name == "shell" -> Icons.Outlined.Terminal
    name == "read_file" -> Icons.Outlined.Description
    name == "write_file" -> Icons.Outlined.Edit
    name == "list_dir" -> Icons.Outlined.Folder
    name == "web_get" -> Icons.Outlined.Language
    name.startsWith("apk_") -> Icons.Outlined.PhoneAndroid
    name == "read_attachment" -> Icons.Outlined.AttachFile
    else -> Icons.Outlined.Build
}

private fun shortArgs(args: String?): String {
    if (args.isNullOrBlank()) return ""
    // The args field is JSON; pull the most descriptive value (cmd / path / url).
    val s = args.trim()
    val keys = listOf("cmd", "command", "path", "url", "input_apk", "src_dir", "apk")
    for (k in keys) {
        val needle = "\"$k\":"
        val idx = s.indexOf(needle)
        if (idx >= 0) {
            val start = s.indexOf('"', idx + needle.length) + 1
            val end = s.indexOf('"', start)
            if (start in 1..end) {
                return s.substring(start, end).take(80)
            }
        }
    }
    return s.take(80)
}

private fun toolResultSummary(name: String, tr: ToolResult?): String {
    if (tr == null) return ""
    val out = tr.output
    val lines = out.count { it == '\n' } + (if (out.isNotEmpty()) 1 else 0)
    val bytes = out.length
    val prefix = if (tr.isError) "error" else "ok"
    val first = out.lineSequence().firstOrNull { it.isNotBlank() }?.take(80).orEmpty()
    return "$prefix \u00b7 ${lines}L \u00b7 ${fmtBytes(bytes)} \u00b7 $first"
}

private fun fmtBytes(n: Int): String = when {
    n < 1024 -> "${n}B"
    n < 1024 * 1024 -> "${n / 1024}KB"
    else -> "${n / (1024 * 1024)}MB"
}

@Composable
private fun Composer(
    value: String,
    onChange: (String) -> Unit,
    sending: Boolean,
    attachments: List<Attachment> = emptyList(),
    uploading: Boolean = false,
    onPick: () -> Unit = {},
    onRemoveAttachment: (Attachment) -> Unit = {},
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LuminarisColors.Surface),
    ) {
        if (attachments.isNotEmpty() || uploading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (att in attachments) {
                    AttachmentChip(att, onRemove = { onRemoveAttachment(att) })
                    Spacer(Modifier.width(6.dp))
                }
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.5.dp,
                        color = LuminarisColors.AccentCyan,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "uploading…",
                        color = LuminarisColors.TextDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPick, enabled = !sending) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "Attach",
                    tint = if (sending) LuminarisColors.Muted else LuminarisColors.AccentCyan,
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                placeholder = { Text("Ask the agent…", color = LuminarisColors.TextDim) },
                singleLine = false,
                maxLines = 4,
                colors = clientFieldColors(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.padding(end = 4.dp))
            IconButton(
                onClick = onSend,
                enabled = !sending && (value.isNotBlank() || attachments.isNotEmpty()),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send",
                    tint = if (sending || (value.isBlank() && attachments.isEmpty()))
                        LuminarisColors.Muted else LuminarisColors.AccentCyan,
                )
            }
        }
    }
}

@Composable
private fun AttachmentChip(att: Attachment, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(LuminarisColors.ToolBubble)
            .border(1.dp, LuminarisColors.AccentCyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            attachmentIcon(att.mime),
            contentDescription = null,
            tint = LuminarisColors.AccentCyan,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            att.name.take(28),
            color = LuminarisColors.Text,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            fmtBytes(att.size.toInt()),
            color = LuminarisColors.TextDim,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Outlined.Close,
            contentDescription = "Remove",
            tint = LuminarisColors.Muted,
            modifier = Modifier
                .size(14.dp)
                .clickable { onRemove() },
        )
    }
}

private fun attachmentIcon(mime: String) = when {
    mime.startsWith("image/") -> Icons.Outlined.Description
    mime.contains("android") || mime.contains("zip") -> Icons.Outlined.PhoneAndroid
    else -> Icons.Outlined.AttachFile
}

@Composable
private fun StopBar(onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LuminarisColors.AccentCyan.copy(alpha = 0.06f))
            .border(
                width = 1.dp,
                color = LuminarisColors.AccentCyan.copy(alpha = 0.25f),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Agent running…",
            color = LuminarisColors.TextDim,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(LuminarisColors.AccentRed.copy(alpha = 0.15f))
                .border(1.dp, LuminarisColors.AccentRed, RoundedCornerShape(16.dp))
                .clickable { onStop() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = null,
                tint = LuminarisColors.AccentRed,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Stop",
                color = LuminarisColors.AccentRed,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun ResumeBar(onResume: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LuminarisColors.AccentRed.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = LuminarisColors.AccentRed.copy(alpha = 0.35f),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Build,
            contentDescription = null,
            tint = LuminarisColors.AccentRed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Upstream error",
                color = LuminarisColors.AccentRed,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                "Agent stopped mid-turn. Tap Resume to continue from where it left off — no context lost.",
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(LuminarisColors.AccentCyan.copy(alpha = 0.15f))
                .border(1.dp, LuminarisColors.AccentCyan, RoundedCornerShape(16.dp))
                .clickable { onResume() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = null,
                tint = LuminarisColors.AccentCyan,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Resume",
                color = LuminarisColors.AccentCyan,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
