package com.bobbot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bobbot.LaunchRequest
import com.bobbot.ui.automations.AutomationsScreen
import com.bobbot.ui.board.BoardScreen
import com.bobbot.ui.bots.BotDetailScreen
import com.bobbot.ui.bots.NewBotScreen
import com.bobbot.ui.chat.ChatScreen
import com.bobbot.ui.groups.GroupChatScreen
import com.bobbot.ui.groups.NewGroupScreen
import com.bobbot.ui.inbox.InboxActions
import com.bobbot.ui.inbox.InboxScreen
import com.bobbot.ui.models.ModelsScreen
import com.bobbot.ui.nav.Route
import com.bobbot.ui.settings.SettingsScreen
import com.bobbot.ui.setup.SetupScreen
import com.bobbot.ui.system.SystemScreen
import com.bobbot.ui.team.TeamScreen
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.update.UpdateSheet
import com.bobbot.update.UpdateCheck

/**
 * The app is a messages app: the inbox is home, every bot is one conversation, and everything
 * else (bot profiles, groups, the network board, automations, settings) is a screen pushed on top.
 */
@Composable
fun AppRoot(launch: LaunchRequest?, onLaunchConsumed: () -> Unit) {
    val vm: AppViewModel = hiltViewModel()
    val boot by vm.boot.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val nav = rememberNavController()

    if (boot == null) { Box(Modifier.fillMaxSize()) ; return }
    val setupDone = boot!!.setupComplete

    LaunchedEffect(launch) {
        val l = launch ?: return@LaunchedEffect
        if (setupDone && l.team) {
            nav.navigate(Route.Team)
        } else if (setupDone && (l.sessionId != null || l.profile != null)) {
            nav.navigate(Route.Chat(sessionId = l.sessionId, profile = l.profile, mainConversation = l.sessionId == null))
        }
        onLaunchConsumed()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startLinkIfEnabled() }
    val finishSetup: () -> Unit = {
        vm.markSetupComplete()
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startLinkIfEnabled()
        nav.navigate(Route.Home) { popUpTo(Route.Setup) { inclusive = true } }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = if (setupDone) Route.Home else Route.Setup,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable<Route.Setup> {
                SetupScreen(onDone = finishSetup)
            }
            composable<Route.Home> {
                InboxScreen(
                    InboxActions(
                        openBot = { nav.openBotChat(it) },
                        openTaskChat = { sid, prof -> nav.navigate(Route.Chat(sid, prof)) },
                        openGroup = { nav.navigate(Route.Group(it)) },
                        openBotProfile = { nav.navigate(Route.BotDetail(it)) },
                        newBot = { nav.navigate(Route.NewBot) },
                        newGroup = { nav.navigate(Route.NewGroup) },
                        openNetwork = { nav.navigate(Route.Board) },
                        openTeam = { nav.navigate(Route.Team) },
                        openAutomations = { nav.navigate(Route.Automations) },
                        openSettings = { nav.navigate(Route.Settings) },
                    ),
                )
            }
            composable<Route.Chat> { entry ->
                val r = entry.toRoute<Route.Chat>()
                ChatScreen(
                    sessionId = r.sessionId, profile = r.profile ?: "default", mainConversation = r.mainConversation,
                    onBack = { nav.popBackStack() },
                    onOpenProfile = { nav.navigate(Route.BotDetail(it)) },
                    onNewTaskChat = { nav.navigate(Route.Chat(null, it)) },
                    onOpenNetwork = { nav.navigate(Route.Board) },
                    onOpenTeam = { nav.navigate(Route.Team) },
                )
            }
            composable<Route.Group> { entry ->
                val r = entry.toRoute<Route.Group>()
                GroupChatScreen(roomId = r.roomId, onBack = { nav.popBackStack() }, onOpenBot = { nav.navigate(Route.BotDetail(it)) })
            }
            composable<Route.NewGroup> {
                NewGroupScreen(onBack = { nav.popBackStack() }, onCreated = { id ->
                    nav.navigate(Route.Group(id)) { popUpTo(Route.Home) }
                })
            }
            composable<Route.BotDetail> { entry ->
                val r = entry.toRoute<Route.BotDetail>()
                BotDetailScreen(
                    name = r.name,
                    onBack = { nav.popBackStack() },
                    onChat = { nav.openBotChat(it) },
                    onNewTaskChat = { nav.navigate(Route.Chat(null, it)) },
                    onOpenSession = { sid, prof -> nav.navigate(Route.Chat(sid, prof)) },
                    onOpenAutomations = { nav.navigate(Route.Automations) },
                )
            }
            composable<Route.NewBot> {
                NewBotScreen(onBack = { nav.popBackStack() }, onCreated = { name ->
                    nav.navigate(Route.Chat(profile = name, mainConversation = true)) { popUpTo(Route.Home) }
                })
            }
            composable<Route.Board> {
                BoardScreen(
                    onBack = { nav.popBackStack() },
                    onOpenTeam = { nav.navigate(Route.Team) },
                    onOpenGroup = { nav.navigate(Route.NewGroup) },
                    onChat = { nav.openBotChat(it) },
                )
            }
            composable<Route.Team> { TeamScreen(onBack = { nav.popBackStack() }, onChat = { nav.openBotChat(it) }) }
            composable<Route.Automations> { AutomationsScreen(onBack = { nav.popBackStack() }, onChat = { nav.openBotChat(it) }) }
            composable<Route.Models> { ModelsScreen(onBack = { nav.popBackStack() }) }
            composable<Route.Settings> {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onSignedOut = { nav.navigate(Route.Setup) { popUpTo(0) { inclusive = true } } },
                    onOpenSystem = { nav.navigate(Route.System) },
                    onOpenModels = { nav.navigate(Route.Models) },
                )
            }
            composable<Route.System> { SystemScreen(onBack = { nav.popBackStack() }) }
        }
    }

    val update = updateState
    if (setupDone && update is UpdateCheck.Available) {
        UpdateSheet(info = update.info, manager = vm.apkUpdateManager, onDismiss = vm::dismissUpdate)
    }
}

/** A bot's ongoing conversation sits directly above the inbox, never stacked on itself. */
private fun NavHostController.openBotChat(profile: String) {
    navigate(Route.Chat(profile = profile, mainConversation = true)) {
        popUpTo(Route.Home)
        launchSingleTop = true
    }
}
