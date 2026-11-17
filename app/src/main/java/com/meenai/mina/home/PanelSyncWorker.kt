package com.meenai.mina.home

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meenai.mina.server.ServerRuntime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Background refresh for connector panels (calendar/mail): re-reads the
 * backend's home snapshot for the panels already on the home screen, so
 * the list is not a full app-open behind. Read-only; panels are never
 * created or deleted here.
 */
class PanelSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!ServerRuntime.enabled) return Result.success()
        if (!ServerRuntime.signedIn(context)) return Result.success()
        val store = PanelsStore.shared(context)
        val targets = store.all().filter { it.kind == "calendar" || it.kind == "mail" }
        if (targets.isEmpty()) return Result.success()
        val root = runCatching { ServerRuntime.obtain(context).api.homeData().jsonObject }.getOrNull()
            ?: return Result.success()
        targets.forEach { p -> runCatching { store.put(sync(context, root, p)) } }
        return Result.success()
    }

    private fun sync(context: Context, root: kotlinx.serialization.json.JsonObject, p: HomePanel): HomePanel {
        val section = root[p.kind] as? JsonObject
            ?: return p.copy(status = "error", updatedAt = System.currentTimeMillis())
        val status = (section["status"] as? JsonPrimitive)?.contentOrNull ?: ""
        val rows = (section["rows"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (p.kind == "calendar") {
                val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val start = (o["start"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (start.isBlank()) title else "$title · ${eventTime(start)}"
            } else {
                val subject = (o["subject"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val from = (o["from"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val unread = (o["unread"] as? JsonPrimitive)?.booleanOrNull ?: false
                buildString {
                    if (unread) append("• ")
                    if (from.isNotBlank()) append(from.substringBefore(' ').ifBlank { from })
                    if (subject.isNotBlank()) append(if (isEmpty()) subject else ": $subject")
                }.ifBlank { null }
            }
        } ?: emptyList()
        val st = when (status) {
            "ok" -> ""
            "not_connected", "reconnect" -> "reconnect"
            else -> "error"
        }
        return p.copy(rows = rows, status = st, updatedAt = System.currentTimeMillis())
    }

    /** ISO 时间写成「d MMM, h:mm a」;解析不了就原样返回。 */
    private fun eventTime(iso: String): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.parse(iso.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2").replace("Z", "+0000")))
    }.getOrDefault(iso)
}
