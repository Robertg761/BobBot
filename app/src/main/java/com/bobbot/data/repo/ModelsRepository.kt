package com.bobbot.data.repo

import com.bobbot.core.net.HermesApi
import com.bobbot.core.net.bool
import com.bobbot.core.net.str
import com.bobbot.data.model.ModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

data class ModelCatalog(val providers: List<ModelProvider>, val currentModel: String, val currentProvider: String) {
    val configured: List<ModelProvider> get() = providers.filter { it.authenticated != false && it.models.isNotEmpty() }
}

sealed interface SetModelResult {
    data object Ok : SetModelResult
    data class ConfirmRequired(val message: String) : SetModelResult
    data class Error(val message: String) : SetModelResult
}

@Singleton
class ModelsRepository @Inject constructor(private val api: HermesApi) {
    private val _catalog = MutableStateFlow<ModelCatalog?>(null)
    val catalog: StateFlow<ModelCatalog?> = _catalog

    suspend fun refresh(profile: String? = null, includeUnconfigured: Boolean = false): ModelCatalog {
        val (providers, cur) = api.modelOptions(profile?.takeIf { it != "default" }, includeUnconfigured)
        val c = ModelCatalog(providers, cur.first, cur.second)
        if (profile == null || profile == "default") _catalog.value = c
        return c
    }

    /** Global default switch (applies to new sessions). Handles the two-phase expensive-model confirm. */
    suspend fun setGlobal(provider: String, model: String, profile: String? = null, confirm: Boolean = false): SetModelResult {
        val r: JsonElement = try { api.setModel(provider, model, profile?.takeIf { it != "default" }, confirm) } catch (e: Exception) { return SetModelResult.Error(e.message ?: "failed") }
        return when {
            r.bool("ok") == true -> SetModelResult.Ok
            r.bool("confirm_required") == true -> SetModelResult.ConfirmRequired(r.str("confirm_message") ?: "This model may be expensive. Confirm?")
            else -> SetModelResult.Error(r.str("error") ?: r.str("detail") ?: "Model switch failed")
        }
    }

    suspend fun auxiliary(profile: String? = null) = api.auxiliaryModels(profile?.takeIf { it != "default" })
    suspend fun setAuxiliary(task: String, provider: String, model: String, profile: String? = null) =
        api.setModel(provider, model, profile?.takeIf { it != "default" }, scope = "auxiliary", task = task)
    suspend fun moa(profile: String? = null) = api.moa(profile?.takeIf { it != "default" })
    suspend fun info(profile: String? = null) = api.modelInfo(profile?.takeIf { it != "default" })
}
