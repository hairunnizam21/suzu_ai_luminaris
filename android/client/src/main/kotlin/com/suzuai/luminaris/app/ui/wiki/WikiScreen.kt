package com.suzuai.luminaris.app.ui.wiki

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.shared.theme.LuminarisColors
import com.suzuai.luminaris.shared.ui.LuminarisCard
import com.suzuai.luminaris.shared.ui.SectionHeader

@Composable
fun WikiScreen() {
    Column(Modifier.fillMaxSize().background(LuminarisColors.Bg).padding(16.dp)) {
        LuminarisCard {
            Column {
                SectionHeader("Wiki")
                Text(
                    "Auto-generated knowledge base from your sessions. " +
                        "Coming soon — the agent will index every tool result here so " +
                        "you can search across runs without re-asking.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "// placeholder — wired to /v1/wiki when the backend ships it",
                    color = LuminarisColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
