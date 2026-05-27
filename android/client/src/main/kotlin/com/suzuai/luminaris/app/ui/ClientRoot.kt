package com.suzuai.luminaris.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.suzuai.luminaris.app.ClientApp
import com.suzuai.luminaris.app.ui.ask.AskScreen
import com.suzuai.luminaris.app.ui.automations.AutomationsScreen
import com.suzuai.luminaris.app.ui.review.ReviewScreen
import com.suzuai.luminaris.app.ui.sessions.SessionsScreen
import com.suzuai.luminaris.app.ui.setup.ClientSetupScreen
import com.suzuai.luminaris.app.ui.sidebar.ClientSidebar
import com.suzuai.luminaris.app.ui.sidebar.ClientTab
import com.suzuai.luminaris.app.ui.wiki.WikiScreen
import com.suzuai.luminaris.shared.theme.LuminarisColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientRoot() {
    val app = ClientApp.instance
    val (url, token) = app.store.pair.collectAsState(initial = "" to "").value

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val configured = url.isNotBlank() && token.isNotBlank()
    var tab by remember(configured) { mutableStateOf(if (configured) ClientTab.Sessions else ClientTab.Setup) }

    LaunchedEffect(configured) {
        if (!configured) tab = ClientTab.Setup
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = LuminarisColors.Surface,
                drawerTonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(0.82f),
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            ) {
                ClientSidebar(
                    current = tab,
                    canShowMain = configured,
                    onPick = {
                        tab = it
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(LuminarisColors.Bg)) {
            ClientTopBar(
                title = tab.label,
                subtitle = if (configured) url else "Not connected",
                onMenu = { scope.launch { drawerState.open() } },
            )
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    ClientTab.Setup -> ClientSetupScreen(onSaved = { tab = ClientTab.Sessions })
                    ClientTab.Sessions -> SessionsScreen(onOpenAsk = { tab = ClientTab.Ask })
                    ClientTab.Ask -> AskScreen()
                    ClientTab.Wiki -> WikiScreen()
                    ClientTab.Review -> ReviewScreen()
                    ClientTab.Automations -> AutomationsScreen()
                }
            }
        }
    }
}
