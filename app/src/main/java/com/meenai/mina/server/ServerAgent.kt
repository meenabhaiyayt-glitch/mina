package com.meenai.mina.server

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One cloud-agent turn for the chat: the backend's orchestrator drives a
 * run synchronously until it completes, fails or waits on an approval;
 * the response carries the events, so steps render after the call
 * returns. Approvals resume through the same endpoint and the loop
 * continues. Device steps have no cloud handler: those fail here and the
 * local agent takes the turn over.
 */
class ServerAgentRun(private val api: MinaApi) {

    private var runId: String? = null
    private var pendingApprovalId: String? = null

    /** The key the confirmation card carries; approve(key)/decline(key) match on it. */
    var pendingKey: Int = 0
        private set

    sealed interface Result {
        data class Done(val answer: String, val stepTitles: List<String>) : Result
        data class NeedsApproval(val key: Int, val body: String) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun start(input: String): Result = drive {
        api.startRun(buildJsonObject {
            put("input", input); put("mode", "ask"); put("timeout_seconds", 240)
        })
    }

    suspend fun decide(approve: Boolean): Result {
        val run = runId ?: return Result.Failed("no_run")
        val approval = pendingApprovalId ?: return Result.Failed("no_approval")
        return drive { api.decideApproval(run, approval, approve) }
    }

    suspend fun cancel() {
        runId?.let { runCatching { api.cancelRun(it) } }
    }

    private suspend fun drive(call: suspend () -> JsonElement): Result {
        val summary = runCatching { call() }.getOrElse { e ->
            return Result.Failed(e.message ?: "network")
        }
        val o = summary as? JsonObject ?: return Result.Failed("bad_response")
        o["run_id"]?.jsonPrimitive?.contentOrNull?.let { runId = it }
        val status = o["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        pendingApprovalId = null
        pendingKey = 0
        val events = o["events"] as? JsonArray
        when (status) {
            "completed" -> {
                val answer = o["final_response"]?.jsonPrimitive?.contentOrNull.orEmpty()
                return if (answer.isBlank()) Result.Failed("empty_response") else Result.Done(answer, stepTitles(events))
            }
            "failed" -> return Result.Failed(o["error"]?.jsonPrimitive?.contentOrNull ?: "failed")
            "waiting_approval" -> {
                val approval = approvalFrom(events) ?: return Result.Failed("approval_missing")
                pendingApprovalId = approval.first
                pendingKey = approval.first.hashCode()
                return Result.NeedsApproval(pendingKey, approval.second)
            }
            "waiting_device" -> return Result.Failed("device_step")
            else -> return Result.Failed(status.ifBlank { "unknown" })
        }
    }

    /** Titles for the read-only steps the run actually performed. */
    private fun stepTitles(events: JsonArray?): List<String> {
        val out = mutableListOf<String>()
        events?.forEach { el ->
            val e = el as? JsonObject ?: return@forEach
            val type = e["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val payload = e["payload"] as? JsonObject
            if (type == "tool_started") {
                val tool = payload?.get("tool")?.jsonPrimitive?.contentOrNull
                if (!tool.isNullOrBlank()) out += tool
            }
        }
        return out
    }

    private fun approvalFrom(events: JsonArray?): Pair<String, String>? {
        events?.forEach { el ->
            val e = el as? JsonObject ?: return@forEach
            if (e["type"]?.jsonPrimitive?.contentOrNull == "approval_required") {
                val payload = e["payload"] as? JsonObject ?: return@forEach
                val id = payload["approval_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val reason = (payload["summary"]?.jsonPrimitive?.contentOrNull.orEmpty() + " " +
                    payload["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()).trim()
                return id to reason
            }
        }
        return null
    }
}

/** The on/off switch lives on the device; the server never decides this. */
object CloudAgentStore {
    private const val FILE = "mina-server"
    private const val KEY = "cloud_agent"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
