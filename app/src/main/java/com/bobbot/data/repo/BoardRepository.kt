package com.bobbot.data.repo

import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.HermesHttpException
import com.bobbot.core.net.jsonOf
import com.bobbot.core.net.child
import com.bobbot.core.net.list
import com.bobbot.core.net.str
import com.bobbot.data.model.BoardComment
import com.bobbot.data.model.BoardEvent
import com.bobbot.data.model.BoardTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The kanban board is Hermes' bot-to-bot bus: a task with `created_by` A and `assignee` B is
 * "A asked B to do something"; a `commented` event with `author` X is "X said something on the thread".
 */
data class BoardActivity(
    val id: String,
    val kind: String,          // assigned | commented | completed | blocked | spawned | ...
    val from: String?,         // sender bot
    val to: String?,           // recipient bot
    val taskId: String,
    val taskTitle: String,
    val text: String,
    val at: String?,
)

@Singleton
class BoardRepository @Inject constructor(private val api: HermesApi) {
    private val _tasks = MutableStateFlow<List<BoardTask>>(emptyList())
    val tasks: StateFlow<List<BoardTask>> = _tasks
    private val _available = MutableStateFlow<Boolean?>(null)
    val available: StateFlow<Boolean?> = _available
    private val _activity = MutableStateFlow<List<BoardActivity>>(emptyList())
    val activity: StateFlow<List<BoardActivity>> = _activity

    suspend fun refresh(): List<BoardTask> {
        return try {
            val t = api.boardTasks()
            _tasks.value = t
            _available.value = true
            t
        } catch (e: HermesHttpException) {
            if (e.code == 404) _available.value = false
            throw e
        }
    }

    suspend fun comments(taskId: String): List<BoardComment> = api.boardTaskComments(taskId)

    suspend fun events(taskId: String): List<BoardEvent> = api.boardTask(taskId).let { r ->
        r.list("events").ifEmpty { r.child("task").list("events") }.map(BoardEvent::from)
    }

    /** Ask bot `to` to do something, on behalf of `from` (a bot name or "you"). */
    suspend fun sendTask(from: String?, to: String, title: String, body: String) =
        api.createBoardTask(title, if (from != null && from != "you") "Robert requests this on behalf of ${BotNames.display(from)}.\n\n$body" else body, to, null)
    suspend fun comment(taskId: String, body: String, author: String?) = api.commentBoardTask(taskId, body, author)
    suspend fun setStatus(taskId: String, status: String) = api.patchBoardTask(taskId, jsonOf("status" to status))
    suspend fun assignees(): List<String> = runCatching { api.boardAssignees() }.getOrDefault(emptyList())

    /** Build a cross-task activity feed by pulling each task's events. Cheap enough for a phone board. */
    suspend fun refreshActivity(limit: Int = 60): List<BoardActivity> {
        val tasks = _tasks.value.ifEmpty { refresh() }
        val out = mutableListOf<BoardActivity>()
        for (t in tasks.take(40)) {
            val detail = runCatching { api.boardTask(t.id) }.getOrNull() ?: continue
            val comments = detail.list("comments").ifEmpty { detail.child("task").list("comments") }.map(BoardComment::from)
            val events = detail.list("events").ifEmpty { detail.child("task").list("events") }.map(BoardEvent::from)
            for (e in events) {
                val (from, to, text) = when (e.kind) {
                    "assigned" -> Triple(t.createdBy, e.payload.str("assignee") ?: t.assignee, "assigned “${t.title}”")
                    "commented" -> {
                        val author = e.author
                        val c = comments.firstOrNull { it.id == e.payload.str("comment_id") }
                        Triple(author, t.assignee?.takeIf { it != author } ?: t.createdBy, c?.body ?: e.payload.str("body") ?: "commented on the task")
                    }
                    "completed" -> Triple(t.assignee, t.createdBy, "completed “${t.title}”")
                    "blocked" -> Triple(t.assignee, t.createdBy, "is blocked on “${t.title}”")
                    "spawned" -> Triple(null, t.assignee, "started working on “${t.title}”")
                    else -> Triple(e.author, null, "${e.kind} on “${t.title}”")
                }
                out += BoardActivity("${t.id}:${e.id}", e.kind, from, to, t.id, t.title, text, e.createdAt)
            }
            if (events.isEmpty()) for (c in comments) {
                out += BoardActivity("${t.id}:c${c.id}", "commented", c.author, t.assignee?.takeIf { it != c.author } ?: t.createdBy, t.id, t.title, c.body, c.createdAt)
            }
        }
        val sorted = out.sortedByDescending { it.at ?: "" }.take(limit)
        _activity.value = sorted
        return sorted
    }
}
