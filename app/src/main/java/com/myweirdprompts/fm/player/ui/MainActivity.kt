package com.myweirdprompts.fm.player.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.myweirdprompts.fm.player.App

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_PLAYER = "open_player" }

    private val player: PlayerConnection by viewModels()
    private val openPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPlayer.value = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true
        val app = application as App
        setContent { MwpTheme { Root(app, player, openPlayer.value) { openPlayer.value = false } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("latest", "Latest", Icons.Filled.NewReleases),
    Tab("channels", "Channels", Icons.Filled.GridView),
    Tab("search", "Search", Icons.Filled.Search),
    Tab("downloads", "Downloads", Icons.Filled.Download),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun Root(app: App, player: PlayerConnection, openPlayer: Boolean, onOpenedPlayer: () -> Unit) {
    val nav = rememberNavController()
    val ui by player.ui.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(Unit) { app.feed.refresh() }
    LaunchedEffect(openPlayer) {
        if (openPlayer) { nav.navigate("player") { launchSingleTop = true }; onOpenedPlayer() }
    }

    Scaffold(
        bottomBar = {
            if (route != "player") Column {
                if (ui.slug != null) MiniPlayer(ui, player) { nav.navigate("player") { launchSingleTop = true } }
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.go(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "latest", modifier = Modifier.padding(pad)) {
            composable("latest") { LatestScreen(app, player) }
            composable("channels") { ChannelsScreen(app) { nav.navigate("channel/$it") } }
            composable("channel/{id}") { ChannelScreen(app, player, it.arguments?.getString("id") ?: "") { nav.popBackStack() } }
            composable("search") { SearchScreen(app, player) }
            composable("downloads") { DownloadsScreen(app, player) }
            composable("settings") { SettingsScreen(app) }
            composable("player") { NowPlayingScreen(app, player, ui) { nav.popBackStack() } }
        }
    }
}

private fun NavHostController.go(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
