package com.meenai.mina.offline

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.AlarmClock
import com.cactus.CactusCompletionParams
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.CactusModelManager
import com.cactus.ChatMessage
import com.cactus.models.ToolParameter
import com.cactus.models.createTool
import com.meenai.mina.R
import com.meenai.mina.home.PanelsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Needle (offline): device pe chalne wala chhota model (Cactus ka local engine).
 * Net na ho tab chhote kaam isi pe jate hain: alarm, timer, app kholna, panel banana.
 * Download ek hi baar hota hai; inference bilkul offline hai, koi API cost nahi.
 */
object NeedleRuntime {
    /** Cactus SDK ke catalog ka sabse chhota tool-calling model. */
    const val SLUG = "qwen3-0.6"
    private const val PREFS = "needle_prefs"
    private const val KEY_ENABLED = "needle_enabled"

    @Volatile private var lm: CactusLM? = null
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Download chal raha hai: card isko dekh ke button badalta hai; screen band ho to bhi download chalta rehta hai. */
    val downloading = MutableStateFlow(false)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, v).apply()
        if (!v) unload()
    }

    fun downloaded(c: Context) = runCatching { CactusModelManager.isModelDownloaded(SLUG) }.getOrDefault(false)

    fun startDownload(c: Context) {
        if (downloading.value) return
        downloadScope.launch {
            downloading.value = true
            runCatching { CactusLM().downloadModel(SLUG) }
            downloading.value = false
        }
    }

    fun online(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    fun unload() {
        runCatching { lm?.unload() }
        lm = null
    }

    /** Ek sawal ka jawab: jo tool device pe chal sake wo chala kar, warna model ka seedha jawab. */
    suspend fun answer(c: Context, goal: String): String = withContext(Dispatchers.Default) {
        val app = c.applicationContext
        val model = lm ?: CactusLM().also {
            it.initializeModel(CactusInitParams(model = SLUG, contextSize = 2048))
            lm = it
        }
        val result = model.generateCompletion(
            messages = listOf(
                ChatMessage(content = SYSTEM, role = "system"),
                ChatMessage(content = goal, role = "user"),
            ),
            params = CactusCompletionParams(tools = tools(), maxTokens = 200),
        )
        if (result == null || !result.success) throw java.io.IOException("needle completion failed")
        val calls = result.toolCalls ?: emptyList()
        if (calls.isEmpty()) {
            return@withContext (result.response ?: "").trim().ifEmpty { app.getString(R.string.needle_no_answer) }
        }
        calls.mapNotNull { call ->
            val name = call.name ?: return@mapNotNull null
            runCatching { execute(app, name, args(call.arguments)) }.getOrElse { app.getString(R.string.needle_fail, name) }
        }.joinToString("\n")
    }

    private const val SYSTEM = "You are Mina's offline assistant running on the user's phone with no internet. " +
        "Do quick tasks only. If one of your tools fits the request, call it. " +
        "Keep answers to one short sentence, in the language the user wrote in. Never ask follow-up questions."

    private fun tools() = listOf(
        createTool(
            name = "set_alarm", description = "Set a phone alarm",
            parameters = mapOf(
                "hour" to ToolParameter(type = "integer", description = "Hour, 0-23", required = true),
                "minute" to ToolParameter(type = "integer", description = "Minute, 0-59", required = true),
                "label" to ToolParameter(type = "string", description = "Optional alarm label", required = false),
            ),
        ),
        createTool(
            name = "set_timer", description = "Start a countdown timer",
            parameters = mapOf(
                "minutes" to ToolParameter(type = "integer", description = "Minutes", required = true),
                "label" to ToolParameter(type = "string", description = "Optional label", required = false),
            ),
        ),
        createTool(
            name = "open_app", description = "Open an installed app by name",
            parameters = mapOf(
                "app_name" to ToolParameter(type = "string", description = "App name, e.g. WhatsApp", required = true),
            ),
        ),
        createTool(
            name = "create_panel", description = "Create a panel card on the app Home screen",
            parameters = mapOf(
                "name" to ToolParameter(type = "string", description = "Panel title", required = true),
                "rows" to ToolParameter(type = "string", description = "Comma separated short lines", required = true),
                "due_date" to ToolParameter(type = "string", description = "Optional due date, YYYY-MM-DD", required = false),
            ),
        ),
    )

    private fun args(a: Any?): JSONObject = try {
        when (a) {
            is String -> JSONObject(a)
            is Map<*, *> -> JSONObject(a as Map<String, Any>)
            else -> JSONObject()
        }
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun execute(app: Context, name: String, a: JSONObject): String = when (name) {
        "set_alarm" -> {
            val hour = a.optInt("hour", -1)
            val minute = a.optInt("minute", 0)
            if (hour !in 0..23 || minute !in 0..59) app.getString(R.string.needle_fail, name)
            else {
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                val label = a.optString("label", "")
                if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (i.resolveActivity(app.packageManager) == null) app.getString(R.string.needle_fail, name)
                else {
                    app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    app.getString(R.string.needle_done_alarm, "%02d:%02d".format(hour, minute))
                }
            }
        }
        "set_timer" -> {
            val minutes = a.optInt("minutes", 0)
            if (minutes <= 0) app.getString(R.string.needle_fail, name)
            else {
                val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                val label = a.optString("label", "")
                if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (i.resolveActivity(app.packageManager) == null) app.getString(R.string.needle_fail, name)
                else {
                    app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    app.getString(R.string.needle_done_timer, minutes.toString())
                }
            }
        }
        "open_app" -> {
            val want = a.optString("app_name", "").trim()
            val pm = app.packageManager
            var best: android.content.pm.ApplicationInfo? = null
            runCatching {
                for (info in pm.getInstalledApplications(0)) {
                    if (pm.getLaunchIntentForPackage(info.packageName) == null) continue
                    val label = pm.getApplicationLabel(info).toString()
                    if (label.equals(want, ignoreCase = true)) { best = info; break }
                    if (best == null && (label.contains(want, ignoreCase = true) || want.contains(label, ignoreCase = true))) best = info
                }
            }
            val hit = best
            val launch = hit?.let { pm.getLaunchIntentForPackage(it.packageName) }
            if (hit == null || launch == null) app.getString(R.string.needle_no_app, want)
            else {
                app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                app.getString(R.string.needle_done_open, pm.getApplicationLabel(hit).toString())
            }
        }
        "create_panel" -> {
            val title = a.optString("name", "").trim()
            if (title.isEmpty()) app.getString(R.string.needle_fail, name)
            else {
                val rows = a.optString("rows", "").split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                val due = a.optString("due_date", "").trim()
                if (due.isNotEmpty()) rows.add("DUE:$due")
                PanelsStore.shared(app).create("custom", title, rows)
                app.getString(R.string.needle_done_panel, title)
            }
        }
        else -> app.getString(R.string.needle_fail, name)
    }
}
