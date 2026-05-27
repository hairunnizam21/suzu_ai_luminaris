package com.suzuai.luminaris.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.app.ClientApp
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors
import com.suzuai.luminaris.shared.ui.LuminarisCard
import com.suzuai.luminaris.shared.ui.SectionHeader
import com.suzuai.luminaris.shared.ui.StatusPill
import kotlinx.coroutines.launch

/**
 * Client first-run setup. Same URL/token contract as the panel — we explicitly
 * use the same endpoint so the panel and client can share a single backend.
 *
 * Once URL+token are saved the client polls /v1/ready. If the panel hasn't
 * filled config yet the client refuses to open Ask/Sessions and tells the
 * user "open the panel app first".
 */
@Composable
fun ClientSetupScreen(onSaved: () -> Unit) {
    val app = ClientApp.instance
    val scope = rememberCoroutineScope()
    val (savedUrl, savedToken) = app.store.pair.collectAsState(initial = "" to "").value

    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var token by remember(savedToken) { mutableStateOf(savedToken) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(LuminarisColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        LuminarisCard {
            Column {
                SectionHeader("Connect to your Luminaris backend")
                Text(
                    "Use the same URL and token your Server Panel app uses. The client " +
                        "stays disabled until the panel fills in SSH + AI provider config.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Backend URL") },
                    placeholder = { Text("http://143.198.211.224:8765") },
                    singleLine = true,
                    colors = clientFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Auth token") },
                    placeholder = { Text("SUZU_TOKEN value") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = clientFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))

                Row {
                    Button(
                        enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuminarisColors.AccentCyan,
                            contentColor = androidx.compose.ui.graphics.Color.Black,
                        ),
                        onClick = {
                            busy = true
                            status = null
                            scope.launch {
                                runCatching {
                                    val cleanUrl = url.trim().trimEnd('/')
                                    val client = BackendClient(cleanUrl, token.trim())
                                    val h = client.health()
                                    val r = client.ready()
                                    app.store.set(cleanUrl, token.trim())
                                    ok = r.ready
                                    status = if (r.ready) {
                                        "Connected — server v${h.version}, config ready."
                                    } else {
                                        "Connected — but waiting on panel to fill: ${r.missing.joinToString()}"
                                    }
                                    onSaved()
                                }.onFailure {
                                    ok = false
                                    status = "Failed: ${it.message ?: it::class.simpleName}"
                                }
                                busy = false
                            }
                        },
                    ) {
                        Text(if (busy) "Connecting…" else "Save & verify", fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.size(12.dp))

                    Button(
                        enabled = !busy && (url.isNotBlank() || token.isNotBlank()),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuminarisColors.SurfaceAlt,
                            contentColor = LuminarisColors.Text,
                        ),
                        onClick = {
                            scope.launch {
                                app.store.clear()
                                url = ""; token = ""
                                status = "Cleared"
                                ok = false
                            }
                        },
                    ) { Text("Clear") }
                }

                if (status != null) {
                    Spacer(Modifier.height(12.dp))
                    StatusPill(ok = ok, label = status!!)
                }
            }
        }
    }
}

@Composable
fun clientFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = LuminarisColors.SurfaceAlt,
    unfocusedContainerColor = LuminarisColors.SurfaceAlt,
    disabledContainerColor = LuminarisColors.SurfaceAlt,
    focusedTextColor = LuminarisColors.Text,
    unfocusedTextColor = LuminarisColors.Text,
    cursorColor = LuminarisColors.AccentCyan,
    focusedLabelColor = LuminarisColors.AccentCyan,
    unfocusedLabelColor = LuminarisColors.TextDim,
    focusedIndicatorColor = LuminarisColors.AccentCyan,
    unfocusedIndicatorColor = LuminarisColors.Outline,
)
