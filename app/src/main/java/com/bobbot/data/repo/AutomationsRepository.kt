package com.bobbot.data.repo

import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.asString
import com.bobbot.core.net.jsonOf
import com.bobbot.core.net.list
import com.bobbot.core.net.str
import com.bobbot.data.model.CronJob
import com.bobbot.data.model.CronRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

data class DeliveryTarget(val id: String, val label: String)

@Singleton
class AutomationsRepository @Inject constructor(private val api: HermesApi) {
    private val _jobs = MutableStateFlow<List<CronJob>>(emptyList())
    val jobs: StateFlow<List<CronJob>> = _jobs

    suspend fun refresh(profile: String? = null): List<CronJob> {
        val list = api.cronJobs(profile?.takeIf { it != "default" })
        _jobs.value = list
        return list
    }

    suspend fun runs(job: CronJob, limit: Int = 20): List<CronRun> = api.cronRuns(job.id, job.profile.takeIf { it != "default" }, limit)

    suspend fun deliveryTargets(profile: String? = null): List<DeliveryTarget> {
        val r = api.cronDeliveryTargets(profile?.takeIf { it != "default" })
        val items = r.list("targets").ifEmpty { r.list("delivery_targets").ifEmpty { r.arr()?.toList() ?: emptyList() } }
        return items.mapNotNull { t ->
            val id = t.str("id") ?: t.str("value") ?: t.str("target") ?: t.asString() ?: return@mapNotNull null
            DeliveryTarget(id, t.str("label") ?: t.str("name") ?: id)
        }.ifEmpty { listOf(DeliveryTarget("local", "Local (no delivery)")) }
    }

    private fun kotlinx.serialization.json.JsonElement.arr() = this as? kotlinx.serialization.json.JsonArray

    /**
     * Create a job. `schedule` accepts a cron expression ("0 9 * * *"), an interval ("every 30m"),
     * or an ISO timestamp for a one-shot reminder. `deliver` is a delivery target id (e.g. "telegram", "ntfy", "local").
     */
    suspend fun create(name: String, prompt: String, schedule: String, deliver: String, profile: String?, skills: List<String> = emptyList()): String? {
        val body = jsonOf(
            "name" to name, "prompt" to prompt, "schedule" to schedule, "deliver" to deliver,
            "profile" to profile?.takeIf { it != "default" }, "skills" to skills.ifEmpty { null },
        )
        val r = api.createCron(body, profile?.takeIf { it != "default" })
        refresh(profile)
        return r.str("id") ?: r.str("job_id")
    }

    suspend fun update(job: CronJob, patch: JsonObject) { api.updateCron(job.id, patch, job.profile.takeIf { it != "default" }); refresh() }
    suspend fun pause(job: CronJob) { api.cronAction(job.id, "pause", job.profile.takeIf { it != "default" }); refresh() }
    suspend fun resume(job: CronJob) { api.cronAction(job.id, "resume", job.profile.takeIf { it != "default" }); refresh() }
    suspend fun trigger(job: CronJob) { api.cronAction(job.id, "trigger", job.profile.takeIf { it != "default" }) }
    suspend fun delete(job: CronJob) { api.deleteCron(job.id, job.profile.takeIf { it != "default" }); refresh() }
}
