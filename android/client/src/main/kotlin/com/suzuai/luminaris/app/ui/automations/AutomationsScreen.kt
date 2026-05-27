package com.suzuai.luminaris.app.ui.automations

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
fun AutomationsScreen() {
    Column(Modifier.fillMaxSize().background(LuminarisColors.Bg).padding(16.dp)) {
        LuminarisCard {
            Column {
                SectionHeader("Automations  •  beta")
                Text(
                    "Schedule sessions to run on a cron. The agent picks up the prompt, executes, " +
                        "and stores results back in Sessions. Coming soon.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "// placeholder — wired to /v1/automations when the backend ships it",
                    color = LuminarisColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
