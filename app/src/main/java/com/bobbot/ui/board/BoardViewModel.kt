package com.bobbot.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bobbot.data.model.BoardComment
import com.bobbot.data.model.BoardTask
import com.bobbot.data.repo.BoardActivity
import com.bobbot.data.repo.BoardRepository
import com.bobbot.data.repo.BotsRepository
import com.bobbot.data.repo.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Detail sheet state for a single board task. */
data class TaskDetailState(
    val task: BoardTask,
    val comments: List<BoardComment> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Boolean = false,
)

data class BoardUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** null = unknown, false = kanban plugin missing on the server (404). */
    val available: Boolean? = null,
    val tasks: List<BoardTask> = emptyList(),
    val activity: List<BoardActivity> = emptyList(),
    val assignees: List<String> = emptyList(),
    val detail: TaskDetailState? = null,
    val sending: Boolean = false,
    val sendError: String? = null,
)

@HiltViewModel
class BoardViewModel @Inject constructor(
    private val board: BoardRepository,
    private val bots: BotsRepository,
    private val chat: ChatRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(BoardUiState())
    val ui: StateFlow<BoardUiState> = _ui.asStateFlow()

    init {
        refresh()
        // Bots talking to each other show up as server-side change events; keep the feed live.
        viewModelScope.launch {
            chat.changes.collect { refresh(quiet = true) }
        }
    }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            val first = _ui.value.tasks.isEmpty() && _ui.value.activity.isEmpty()
            _ui.update { it.copy(loading = first && !quiet, refreshing = !quiet, error = null) }
            try {
                val tasks = board.refresh()
                val activity = runCatching { board.refreshActivity() }.getOrDefault(emptyList())
                val names = loadAssignees()
                _ui.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        available = true,
                        tasks = tasks,
                        activity = activity,
                        assignees = names,
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        available = board.available.value,
                        error = e.message ?: "Could not load the board",
                    )
                }
            }
        }
    }

    private suspend fun loadAssignees(): List<String> {
        val fromBoard = board.assignees()
        if (fromBoard.isNotEmpty()) return fromBoard.distinct()
        val cached = bots.bots.value
        val list = if (cached.isNotEmpty()) cached else runCatching { bots.refresh() }.getOrDefault(emptyList())
        return list.map { it.name }
    }

    /** Bot names for the "on behalf of" picker (falls back to whatever the board knows). */
    fun botNames(): List<String> {
        val cached = bots.bots.value.map { it.name }
        return if (cached.isNotEmpty()) cached else _ui.value.assignees
    }

    fun openTask(task: BoardTask) {
        _ui.update { it.copy(detail = TaskDetailState(task = task, loading = true)) }
        loadComments(task.id)
    }

    fun closeTask() = _ui.update { it.copy(detail = null) }

    private fun loadComments(taskId: String) {
        viewModelScope.launch {
            try {
                val comments = board.comments(taskId)
                _ui.update { s ->
                    if (s.detail?.task?.id != taskId) s
                    else s.copy(detail = s.detail.copy(comments = comments, loading = false, error = null))
                }
            } catch (e: Exception) {
                _ui.update { s ->
                    if (s.detail?.task?.id != taskId) s
                    else s.copy(detail = s.detail.copy(loading = false, error = e.message ?: "Could not load comments"))
                }
            }
        }
    }

    fun addComment(body: String) {
        val detail = _ui.value.detail ?: return
        if (body.isBlank()) return
        _ui.update { s -> s.copy(detail = s.detail?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                board.comment(detail.task.id, body.trim(), author = "robert")
                val comments = board.comments(detail.task.id)
                _ui.update { s -> s.copy(detail = s.detail?.copy(comments = comments, busy = false)) }
                refresh(quiet = true)
            } catch (e: Exception) {
                _ui.update { s -> s.copy(detail = s.detail?.copy(busy = false, error = e.message ?: "Could not post the comment")) }
            }
        }
    }

    fun setStatus(status: String) {
        val detail = _ui.value.detail ?: return
        if (detail.task.status == status) return
        _ui.update { s -> s.copy(detail = s.detail?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                board.setStatus(detail.task.id, status)
                _ui.update { s ->
                    s.copy(detail = s.detail?.copy(task = s.detail.task.copy(status = status), busy = false))
                }
                refresh(quiet = true)
            } catch (e: Exception) {
                _ui.update { s -> s.copy(detail = s.detail?.copy(busy = false, error = e.message ?: "Could not change the status")) }
            }
        }
    }

    fun sendTask(from: String?, to: String, title: String, body: String, onDone: () -> Unit) {
        if (to.isBlank() || title.isBlank()) return
        _ui.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            try {
                board.sendTask(from?.takeIf { it.isNotBlank() }, to, title.trim(), body.trim())
                _ui.update { it.copy(sending = false, sendError = null) }
                onDone()
                refresh(quiet = true)
            } catch (e: Exception) {
                _ui.update { it.copy(sending = false, sendError = e.message ?: "Could not send the task") }
            }
        }
    }

    fun clearSendError() = _ui.update { it.copy(sendError = null) }
}
