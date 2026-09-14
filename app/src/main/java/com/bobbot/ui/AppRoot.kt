package com.bobbot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bobbot.LaunchRequest
import com.bobbot.core.net.HermesClient
import com.bobbot.data.repo.ChatRepository
import com.bobbot.data.repo.CompletionNotice
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
import com.bobbot.ui.share.SharePayload
import com.bobbot.ui.share.ShareTargetSheet
import com.bobbot.ui.system.SystemScreen
import com.bobbot.ui.team.TeamScreen
import com.bobbot.ui.theme.BobColors
import com.bobbot.ui.update.UpdateSheet
import com.bobbot.update.UpdateCheck
import kotlinx.coroutines.delay

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

    val bots by vm.bots.collectAsStateWithLifecycle()
    /** A share waiting for the user to pick a bot. */
    var pendingShare by remember { mutableStateOf<SharePayload?>(null) }
    /** A dashboard address scanned from a pairing QR, handed to setup. */
    var pairUrl by remember { mutableStateOf<String?>(null) }
    /** The same, when the phone is already paired with a different server and has to be asked. */
    var switchTo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(launch) {
        val l = launch ?: return@LaunchedEffect
        val pair = pairTarget(l.pair)
        when {
            l.share != null && setupDone -> { pendingShare = l.share; vm.refreshBots() }
            pair != null -> if (setupDone) switchTo = pair else pairUrl = pair
            setupDone && l.team -> nav.navigate(Route.Team)
            setupDone && (l.sessionId != null || l.profile != null) ->
                nav.navigate(Route.Chat(sessionId = l.sessionId, profile = l.profile, mainConversation = l.sessionId == null))
        }
        onLaunchConsumed()
    }

    val sendShare: (String) -> Unit = { profile ->
        pendingShare?.let { vm.offerShare(it) }
        pendingShare = null
        // Always a fresh chat entry: if that conversation is already on screen its composer would
        // otherwise never rebind and the share would sit in the inbox unread.
        nav.navigate(Route.Chat(profile = profile, mainConversation = true)) { popUpTo(Route.Home) }
    }

    // One bot is not a choice. Wait for the roster before deciding, so a cold start does not flash the picker.
    LaunchedEffect(pendingShare, bots) {
        if (pendingShare != null && bots.size == 1) sendShare(bots.first().name)
    }

    val backEntry by nav.currentBackStackEntryAsState()
    val openChat = remember(backEntry) { backEntry?.takeIf { it.destination.hasRoute(Route.Chat::class) }?.toRoute<Route.Chat>() }
    val onInbox = backEntry?.destination?.hasRoute(Route.Home::class) == true
    var banner by remember { mutableStateOf<CompletionNotice?>(null) }
    var bannerSeq by remember { mutableIntStateOf(0) }
    val whereTheyAre by rememberUpdatedState(openChat to onInbox)

    LaunchedEffect(Unit) {
        vm.replies.collect { notice ->
            val (chatOnScreen, atInbox) = whereTheyAre
            // The inbox already marks the row unread, and the open chat shows the reply itself.
            if (atInbox || notice.isOnScreen(chatOnScreen)) return@collect
            banner = notice
            bannerSeq++
        }
    }
    LaunchedEffect(bannerSeq) {
        if (banner != null) { delay(5_000); banner = null }
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
                SetupScreen(onDone = { pairUrl = null; finishSetup() }, pairUrl = pairUrl)
            }
            composable<Route.Home> {
                InboxScreen(
                    InboxActions(
                        openBot = { nav.openBotChat(it, fromHome = true) },
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

        ReplyBanner(
            notice = banner,
            onOpen = { n ->
                banner = null
                if (n.storedId == null || n.title == ChatRepository.MAIN_CHAT_TITLE) nav.openBotChat(n.profile)
                else nav.navigate(Route.Chat(sessionId = n.storedId, profile = n.profile))
            },
            onDismiss = { banner = null },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    pendingShare?.let { payload ->
        if (bots.size != 1) ShareTargetSheet(bots = bots, payload = payload, onPick = sendShare, onDismiss = { pendingShare = null })
    }

    switchTo?.let { url ->
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Switch to $url?") },
            text = { Text("BobBot will sign out of the Hermes server it uses now and connect to this one instead.") },
            confirmButton = {
                TextButton(onClick = {
                    switchTo = null
                    vm.switchServer(url) {
                        pairUrl = url
                        nav.navigate(Route.Setup) { popUpTo(0) { inclusive = true } }
                    }
                }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Cancel") } },
            containerColor = BobColors.SurfaceRaised,
        )
    }

    val update = updateState
    if (setupDone && update is UpdateCheck.Available) {
        UpdateSheet(info = update.info, manager = vm.apkUpdateManager, onDismiss = vm::dismissUpdate)
    }
}

/** A pairing link may only point at an http(s) dashboard; anything else is not ours to open. */
internal fun pairTarget(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    if (s.contains("://") && !s.startsWith("http://") && !s.startsWith("https://")) return null
    return HermesClient.normalizeBaseUrl(s)
}

/**
 * True when a banner would announce the conversation already on screen. A bot's ongoing chat is
 * matched by profile, because the screen resolves its session id itself; a task chat is matched by
 * the session it was opened with.
 */
internal fun CompletionNotice.isOnScreen(open: Route.Chat?): Boolean {
    val route = open ?: return false
    if ((route.profile ?: "default") != profile) return false
    return if (route.sessionId == null) route.mainConversation == (title == ChatRepository.MAIN_CHAT_TITLE)
    else route.sessionId == storedId
}

/**
 * Open a bot's ongoing conversation. From the inbox it sits directly above Home; from any other
 * screen (Team, Board, Automations, a profile) it stacks on top so Back returns there.
 */
private fun NavHostController.openBotChat(profile: String, fromHome: Boolean = false) {
    navigate(Route.Chat(profile = profile, mainConversation = true)) {
        if (fromHome) popUpTo(Route.Home)
        launchSingleTop = true
    }
}
