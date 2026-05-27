package com.suzuai.luminaris.panel.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.suzuai.luminaris.panel.PanelApp
import com.suzuai.luminaris.panel.ui.setup.luminarisFieldColors
import com.suzuai.luminaris.shared.data.AdminConfig
import com.suzuai.luminaris.shared.data.AiProviderConfig
import com.suzuai.luminaris.shared.data.SshConfig
import com.suzuai.luminaris.shared.data.VerifyResponse
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors
import com.suzuai.luminaris.shared.ui.LuminarisCard
import com.suzuai.luminaris.shared.ui.SectionHeader
import com.suzuai.luminaris.shared.ui.StatusPill
import kotlinx.coroutines.launch

/**
 * The big "fill in everything" screen. Two cards (SSH + AI provider) plus a
 * verify button at the bottom that round-trips to the backend and reports
 * per-row OK/fail status.
 */
@Composable
fun ConfigScreen() {
    val app = PanelApp.instance
    val scope = rememberCoroutineScope()
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value
    val client = remember(url, token) { BackendClient(url, token) }

    var config by remember { mutableStateOf(AdminConfig()) }
    var verify by remember { mutableStateOf<VerifyResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(url, token) {
        if (url.isBlank() || token.isBlank()) return@LaunchedEffect
        runCatching { client.getConfig() }
            .onSuccess { config = it; loaded = true }
            .onFailure { error = "Load failed: ${it.message}" }
    }

    Column(
        Modifier.fillMaxSize().background(LuminarisColors.Bg).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        SshCard(
            ssh = config.ssh,
            onChange = { config = config.copy(ssh = it) },
        )
        Spacer(Modifier.height(12.dp))
        AiProviderCard(
            ai = config.aiProvider,
            onChange = { config = config.copy(aiProvider = it) },
        )
        Spacer(Modifier.height(12.dp))

        LuminarisCard {
            Column {
                SectionHeader("Verify & save")
                Text(
                    "Saves config to the backend, then runs a dry-run: SSH connect + " +
                        "model.complete(\"hello\"). Each row reports its own status.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))

                Row {
                    Button(
                        enabled = !busy && loaded,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuminarisColors.AccentCyan,
                            contentColor = androidx.compose.ui.graphics.Color.Black,
                        ),
                        onClick = {
                            busy = true
                            error = null
                            verify = null
                            scope.launch {
                                runCatching {
                                    client.putConfig(config)
                                    verify = client.verifyConfig()
                                }.onFailure {
                                    error = "Save/verify failed: ${it.message}"
                                }
                                busy = false
                            }
                        },
                    ) { Text(if (busy) "Verifying…" else "Save & verify", fontWeight = FontWeight.SemiBold) }

                    Spacer(Modifier.padding(6.dp))

                    Button(
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuminarisColors.SurfaceAlt,
                            contentColor = LuminarisColors.AccentRed,
                        ),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching { client.wipeConfig() }
                                    .onSuccess { config = it; verify = null }
                                    .onFailure { error = "Wipe failed: ${it.message}" }
                                busy = false
                            }
                        },
                    ) { Text("Wipe", fontWeight = FontWeight.SemiBold) }
                }

                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    StatusPill(ok = false, label = error!!)
                }

                verify?.let { v ->
                    Spacer(Modifier.height(12.dp))
                    v.rows.forEach { row ->
                        StatusPill(ok = row.ok, label = "${row.name} — ${row.detail}")
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SshCard(ssh: SshConfig, onChange: (SshConfig) -> Unit) {
    LuminarisCard {
        Column {
            SectionHeader("Remote SSH host")
            Text(
                "All shell + file tools run on this box, not on the FastAPI VPS. " +
                    "Provide root or any sudoer.",
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = ssh.host, onValueChange = { onChange(ssh.copy(host = it)) },
                label = { Text("Host / IP") },
                placeholder = { Text("143.198.211.224") },
                singleLine = true, colors = luminarisFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedTextField(
                    value = ssh.port.toString(),
                    onValueChange = { txt -> onChange(ssh.copy(port = txt.toIntOrNull() ?: ssh.port)) },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, colors = luminarisFieldColors(),
                    modifier = Modifier.padding(end = 8.dp).fillMaxWidth(0.4f),
                )
                OutlinedTextField(
                    value = ssh.user, onValueChange = { onChange(ssh.copy(user = it)) },
                    label = { Text("Username") },
                    singleLine = true, colors = luminarisFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            // For simplicity we only expose password mode here. Private key is
            // still supported by the model — paste a PEM into auth_mode=key via
            // a future settings drawer.
            OutlinedTextField(
                value = ssh.password, onValueChange = { onChange(ssh.copy(password = it, authMode = "password")) },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, colors = luminarisFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ssh.workspace, onValueChange = { onChange(ssh.copy(workspace = it)) },
                label = { Text("Workspace directory") },
                placeholder = { Text("~/suzu_workspace") },
                singleLine = true, colors = luminarisFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AiProviderCard(ai: AiProviderConfig, onChange: (AiProviderConfig) -> Unit) {
    LuminarisCard {
        Column {
            SectionHeader("AI provider")
            Text(
                "OpenAI-compatible covers fiqstr, OpenRouter, Together, Groq, LiteLLM. " +
                    "Anthropic uses Anthropic's native API.",
                color = LuminarisColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            // Simple kind selector via segmented row
            Row(Modifier.fillMaxWidth()) {
                listOf("openai_compat", "anthropic", "openai").forEach { kind ->
                    val active = ai.kind == kind
                    Button(
                        modifier = Modifier.padding(end = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) LuminarisColors.AccentCyan else LuminarisColors.SurfaceAlt,
                            contentColor = if (active) androidx.compose.ui.graphics.Color.Black else LuminarisColors.Text,
                        ),
                        onClick = { onChange(ai.copy(kind = kind)) },
                    ) { Text(kind, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = ai.baseUrl, onValueChange = { onChange(ai.copy(baseUrl = it)) },
                label = { Text("Base URL") },
                placeholder = { Text("https://core.fiqstr.com/v1") },
                singleLine = true, colors = luminarisFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ai.model, onValueChange = { onChange(ai.copy(model = it)) },
                label = { Text("Model") },
                placeholder = { Text("fiqstr/claude-opus-4.7-thinking-agent") },
                singleLine = true, colors = luminarisFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ai.apiKey, onValueChange = { onChange(ai.copy(apiKey = it)) },
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, colors = luminarisFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
