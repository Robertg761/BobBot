package com.bobbot.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.auth.AuthManager
import com.bobbot.core.auth.TokenStore
import com.bobbot.data.model.Bot
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.data.prefs.ConnectionPrefs
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ChatRepository
import com.bobbot.data.repo.CompletionNotice
import com.bobbot.service.LinkService
import com.bobbot.ui.share.SharePayload
import com.bobbot.ui.share.ShareInbox
import com.bobbot.update.ApkUpdateManager
import com.bobbot.update.UpdateCheck
import com.bobbot.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: AppPrefs,
    private val tokens: TokenStore,
    private val updates: UpdateRepository,
    private val auth: AuthManager,
    private val botsRepo: BotsRepository,
    private val chat: ChatRepository,
    private val shares: ShareInbox,
    val apkUpdateManager: ApkUpdateManager,
) : ViewModel() {
    val updateState: StateFlow<UpdateCheck> = updates.state
    fun dismissUpdate() = updates.dismiss()
    private val _boot = MutableStateFlow<ConnectionPrefs?>(null)
    val boot: StateFlow<ConnectionPrefs?> = _boot

    /** The share picker's list; already loaded whenever the inbox has been open. */
    val bots: StateFlow<List<Bot>> = botsRepo.bots

    /**
     * Bot replies worth an in-app banner. Notifications are suppressed while BobBot is in the
     * foreground, so this is the only cue a chat the user is not reading has finished; if they
     * turned notifications off entirely, stay quiet here too.
     */
    val replies: Flow<CompletionNotice> = chat.completions.filter { prefs.currentNotifications().enabled }

    init {
        viewModelScope.launch {
            tokens.restoreMode()
            val c = prefs.current()
            _boot.value = c
            if (c.setupComplete && prefs.currentNotifications().enabled && !LinkService.isRunning) {
                runCatching { LinkService.start(ctx) }
            }
        }
        viewModelScope.launch {
            apkUpdateManager.pruneIfInstalled(ctx, updates.currentVersionName)
            updates.autoCheck()
        }
    }

    /** Called when the user finishes setup; starts the background link if they opted in. */
    fun startLinkIfEnabled() {
        viewModelScope.launch {
            if (prefs.currentNotifications().enabled) runCatching { LinkService.start(ctx) }
        }
    }

    fun refreshBots() {
        viewModelScope.launch { runCatching { botsRepo.refresh() } }
    }

    /** Hand a share to the chat the user picked; the chat fills its composer when it opens. */
    fun offerShare(payload: SharePayload) = shares.offer(payload)

    /**
     * A pairing QR from a different dashboard. The tokens belong to the old server, so this is a
     * sign-out with the new address already filled in.
     */
    fun switchServer(url: String, onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setBaseUrl(url)
            runCatching { auth.signOut() }
            prefs.setSetupComplete(false)
            onDone()
        }
    }

    fun markSetupComplete() {
        viewModelScope.launch {
            prefs.setSetupComplete(true)
            _boot.value = prefs.current()
        }
    }
}
