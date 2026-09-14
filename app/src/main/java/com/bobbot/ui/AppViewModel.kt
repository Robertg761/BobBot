package com.bobbot.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.core.auth.TokenStore
import com.bobbot.data.prefs.AppPrefs
import com.bobbot.data.prefs.ConnectionPrefs
import com.bobbot.service.LinkService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val prefs: AppPrefs,
    private val tokens: TokenStore,
) : ViewModel() {
    private val _boot = MutableStateFlow<ConnectionPrefs?>(null)
    val boot: StateFlow<ConnectionPrefs?> = _boot

    init {
        viewModelScope.launch {
            tokens.restoreMode()
            val c = prefs.current()
            _boot.value = c
            if (c.setupComplete && prefs.currentNotifications().enabled && !LinkService.isRunning) {
                runCatching { LinkService.start(ctx) }
            }
        }
    }

    /** Called when the user finishes setup; starts the background link if they opted in. */
    fun startLinkIfEnabled() {
        viewModelScope.launch {
            if (prefs.currentNotifications().enabled) runCatching { LinkService.start(ctx) }
        }
    }

    fun markSetupComplete() {
        viewModelScope.launch {
            prefs.setSetupComplete(true)
            _boot.value = prefs.current()
        }
    }
}
