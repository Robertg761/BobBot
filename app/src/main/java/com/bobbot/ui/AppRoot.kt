package com.bobbot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bobbot.LaunchRequest
import com.bobbot.ui.automations.AutomationsScreen
import com.bobbot.ui.board.BoardScreen
import com.bobbot.ui.bots.BotDetailScreen
import com.bobbot.ui.bots.BotsScreen
import com.bobbot.ui.bots.NewBotScreen
import com.bobbot.ui.chat.ChatScreen
import com.bobbot.ui.models.ModelsScreen
import com.bobbot.ui.nav.Route
import com.bobbot.ui.relay.RelayScreen
import com.bobbot.ui.sessions.SessionsScreen
import com.bobbot.ui.settings.SettingsScreen
import com.bobbot.ui.setup.SetupScreen
import com.bobbot.ui.system.SystemScreen
import com.bobbot.ui.theme.BobColors

private data class Tab(val route: Route, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Route.Home, "Chats", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    Tab(Route.Bots, "Bots", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    Tab(Route.Board, "Network", Icons.Outlined.Hub, Icons.Rounded.Hub),
    Tab(Route.Automations, "Automations", Icons.Outlined.Schedule, Icons.Rounded.Schedule),
    Tab(Route.Settings, "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

@Composable
fun AppRoot(launch: LaunchRequest?, onLaunchConsumed: () -> Unit) {
    val vm: AppViewModel = hiltViewModel()
    val boot by vm.boot.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    if (boot == null) { Box(Modifier.fillMaxSize()) ; return }
    val setupDone = boot!!.setupComplete

    LaunchedEffect(launch) {
        val l = launch ?: return@LaunchedEffect
        if (setupDone && (l.sessionId != null || l.profile != null)) {
            nav.navigate(Route.Chat(sessionId = l.sessionId, profile = l.profile))
        }
        onLaunchConsumed()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startLinkIfEnabled() }
    val finishSetup: () -> Unit = {
        vm.markSetupComplete()
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startLinkIfEnabled()
        nav.navigate(Route.Home) { popUpTo(Route.Setup) { inclusive = true } }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination
    val showBar = tabs.any { t -> dest?.hasRoute(t.route::class) == true }

    Scaffold(
        containerColor = BobColors.Bg,
        bottomBar = {
            AnimatedVisibility(visible = showBar) {
                NavigationBar(containerColor = BobColors.Surface, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        val selected = dest?.hasRoute(t.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) t.selectedIcon else t.icon, t.label) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BobColors.Accent,
                                selectedTextColor = BobColors.Text,
                                indicatorColor = BobColors.AccentSoft,
                                unselectedIconColor = BobColors.TextMuted,
                                unselectedTextColor = BobColors.TextMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = if (setupDone) Route.Home else Route.Setup,
            modifier = Modifier.fillMaxSize().padding(bottom = if (showBar) pad.calculateBottomPadding() else 0.dp),
        ) {
            composable<Route.Setup> {
                SetupScreen(onDone = finishSetup)
            }
            composable<Route.Home> {
                SessionsScreen(
                    onOpenChat = { sid, prof -> nav.navigate(Route.Chat(sid, prof)) },
                    onNewChat = { prof -> nav.navigate(Route.Chat(null, prof)) },
                    onOpenBots = { nav.navigate(Route.Bots) },
                )
            }
            composable<Route.Chat> { entry ->
                val r = entry.toRoute<Route.Chat>()
                ChatScreen(
                    sessionId = r.sessionId, profile = r.profile ?: "default",
                    onBack = { nav.popBackStack() },
                    onOpenModels = { nav.navigate(Route.Models) },
                )
            }
            composable<Route.Sessions> {
                SessionsScreen(
                    onOpenChat = { sid, prof -> nav.navigate(Route.Chat(sid, prof)) },
                    onNewChat = { prof -> nav.navigate(Route.Chat(null, prof)) },
                    onOpenBots = { nav.navigate(Route.Bots) },
                )
            }
            composable<Route.Bots> {
                BotsScreen(
                    onOpenBot = { nav.navigate(Route.BotDetail(it)) },
                    onNewBot = { nav.navigate(Route.NewBot) },
                    onChat = { nav.navigate(Route.Chat(null, it)) },
                )
            }
            composable<Route.BotDetail> { entry ->
                val r = entry.toRoute<Route.BotDetail>()
                BotDetailScreen(
                    name = r.name,
                    onBack = { nav.popBackStack() },
                    onChat = { nav.navigate(Route.Chat(null, it)) },
                    onOpenSession = { sid, prof -> nav.navigate(Route.Chat(sid, prof)) },
                )
            }
            composable<Route.NewBot> {
                NewBotScreen(onBack = { nav.popBackStack() }, onCreated = { name ->
                    nav.navigate(Route.BotDetail(name)) { popUpTo(Route.Bots) }
                })
            }
            composable<Route.Board> {
                BoardScreen(onOpenRelay = { nav.navigate(Route.Relay) }, onChat = { nav.navigate(Route.Chat(null, it)) })
            }
            composable<Route.Relay> { RelayScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Automations> { AutomationsScreen(onChat = { nav.navigate(Route.Chat(null, it)) }) }
            composable<Route.Models> { ModelsScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Settings> {
                SettingsScreen(
                    onBack = null,
                    onSignedOut = { nav.navigate(Route.Setup) { popUpTo(0) { inclusive = true } } },
                    onOpenSystem = { nav.navigate(Route.System) },
                    onOpenModels = { nav.navigate(Route.Models) },
                )
            }
            composable<Route.System> { SystemScreen(onBack = { nav.popBackStack() }) }
        }
    }
}
