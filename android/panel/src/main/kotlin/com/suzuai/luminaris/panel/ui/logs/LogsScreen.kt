package com.suzuai.luminaris.panel.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.panel.PanelApp
import com.suzuai.luminaris.shared.data.LogEvent
import com.suzuai.luminaris.shared.net.BackendClient
import com.suzuai.luminaris.shared.theme.LuminarisColors

/**
 * Live SSE log viewer. Auto-scrolls to bottom unless the user has scrolled up
 * to read older lines.
 */
@Composable
fun LogsScreen() {
    val app = PanelApp.instance
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value
    val client = remember(url, token) { BackendClient(url, token) }

    val items = remember { mutableStateListOf<LogEvent>() }
    val listState = rememberLazyListState()

    LaunchedEffect(url, token) {
        if (url.isBlank() || token.isBlank()) return@LaunchedEffect
        runCatching {
            client.streamLogs().collect { ev ->
                items.add(ev)
                if (items.size > 1000) items.removeAt(0)
            }
        }
    }

    // Auto-scroll when new lines arrive AND the user is already near the bottom.
    LaunchedEffect(items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val total = info.totalItemsCount
        if (total - lastVisible <= 3) {
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(LuminarisColors.Bg)) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    "Waiting for log events…\n\n" +
                        "Logs include agent steps, SSH commands, APK builds, and provider errors.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(items) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun LogRow(ev: LogEvent) {
    val color = when (ev.level.uppercase()) {
        "ERROR" -> LuminarisColors.AccentRed
        "WARN", "WARNING" -> LuminarisColors.AccentAmber
        "INFO" -> LuminarisColors.AccentGreen
        "DEBUG" -> LuminarisColors.TextDim
        else -> LuminarisColors.Text
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 4.dp),
    ) {
        Text(
            "[${ev.source}]",
            color = LuminarisColors.AccentCyan,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.padding(start = 6.dp))
        Text(
            ev.message,
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
