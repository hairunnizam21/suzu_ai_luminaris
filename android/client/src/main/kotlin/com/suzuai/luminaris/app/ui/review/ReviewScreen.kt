package com.suzuai.luminaris.app.ui.review

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
fun ReviewScreen() {
    Column(Modifier.fillMaxSize().background(LuminarisColors.Bg).padding(16.dp)) {
        LuminarisCard {
            Column {
                SectionHeader("Review")
                Text(
                    "Devin-style PR review: the agent reads a diff, leaves inline comments, " +
                        "and proposes patches. Coming soon — wire your git provider on the panel app first.",
                    color = LuminarisColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "// placeholder — wired to /v1/review when the backend ships it",
                    color = LuminarisColors.Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
