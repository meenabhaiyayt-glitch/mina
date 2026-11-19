# Mina v0.1.65 - Home screen redesign (complete files)

Build: SUCCESSFUL (assemblePerf, versionCode 66). Unit tests: PASS (2724 tests, 0 failures, 0 errors, 8 skipped).
Release: local commit 54f8bf9 on main; push to GitHub blocked ("Your account is suspended", 403), so v0.1.65 is not published yet. APKs staged: Mina-v0.1.65-universal.apk (92,933,545 B), Mina-v0.1.65-arm64-v8a.apk (80,329,470 B), cert 96b2deb6 (update-compatible with v0.1.57+).

## Touched files
1. app/build.gradle.kts (diff)
2. app/src/main/AndroidManifest.xml (diff)
3. NEW home/WeatherRepository.kt - FusedLocation balanced + Geocoder place name, open-meteo (no key), DataStore cache, refresh on resume / every 30 min / after a 5km move
4. NEW home/NotificationRepository.kt - deleted-notification ids persisted in DataStore, Undo support
5. NEW home/HomeViewModel.kt - Home state (notifications, weather, loading, permission)
6. NEW ui/components/HomeCards.kt - SwipeToDeleteCard, NotificationCard (FlowRow sentence: verb chip + text + inline app icon), WeatherCardView/WeatherTile, GlassCompactTabs (56dp, min 88dp tabs), previews
7. ui/HomeScreen.kt (rewritten) - "Your apps" + "Loading apps" removed, section labels removed, LazyColumn 20dp padding / 14dp gap / 28dp corners, order: system cards > swipeable notifications > weather card > panels + create panel, Snackbar "Deleted"/Undo, staggered entry
8. ui/components/Glass.kt (diff) - staggerIn slide param
9. theme/Icons.kt + strings en/hi (diff) - weather icons + 6 new strings

## Checklist vs the spec
- Your apps / Loading apps fully removed (UI + loadApps logic). Top bar unchanged. "Create a new panel" kept with its click action.
- Notification cards: frosted glass over Haze, trash reveal behind, rotation up to -3deg + 0.98 scale, 40%-width or fast-fling threshold, light haptic crossing it, animateItem gap-closing, fade+slide-down entrance, persistent delete + 4s Undo, empty area collapses so weather moves up.
- Weather card: icon matches condition, place name header, two tiles (condition 15sp grey + temp 36sp SemiBold; Sunset / after-sunset Sunrise, 12/24h per device), automatic location with friendly rationale + Enable location link, DataStore instant show + AnimatedContent crossfade, shimmer only on first load.
- Background gradient, warm glow, film grain, press scale 0.97 with no ripple, staggered entry reuse the v0.1.60 glass system.
- Only the Home screen changed; no other screen, feature, or navigation touched.

---

## FILE: app/src/main/java/com/meenai/mina/home/WeatherRepository.kt

```kotlin
package com.meenai.mina.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Home 的常驻天气卡:自动定位(Fused,balanced;无 GMS 时退回网络定位)+ 反地理编码取地名,
 * open-meteo 免密钥拿实时温度/天气代码/日出日落,结果存进 DataStore,启动先把缓存画出来再原地淡换。
 * 30 分钟一刷;定位和上次差过 5 公里就立刻重取。
 */
data class WeatherData(
    val place: String,
    val temp: Double,
    /** WMO 天气代码。 */
    val code: Int,
    /** 白天 1 / 夜里 0,选图标用。 */
    val isDay: Boolean,
    /** 今天日落,open-meteo 的本地时刻 "2026-11-10T17:34"。 */
    val sunset: String?,
    /** 下一次日出(早上是今天,日落后是明天)。 */
    val nextSunrise: String?,
    /** 今天的日出,判断现在在哪一段用。 */
    val todaySunrise: String?,
    val lat: Double,
    val lon: Double,
    /** 取数据的时刻。 */
    val at: Long,
)

private val Context.homeWeatherStore by preferencesDataStore(name = "home_weather")
private val WEATHER_JSON = stringPreferencesKey("weather_json")

class WeatherRepository(private val context: Context) {

    suspend fun cached(): WeatherData? = readCache()

    /** 现在是否已授权精确定位(Coarse 也算:balananced 精度本来就是大致位置)。 */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 刷新:先看缓存,新鲜(半小时内)且没挪远(5 公里内)就直接用;
     * 拿到新定位、或缓存太旧、或缓存为空才重新取。[force] 跳过新鲜度检查(授权后的第一次刷新)。
     */
    suspend fun refresh(force: Boolean = false): WeatherData? = withContext(Dispatchers.IO) {
        val old = readCache()
        if (!force && old != null && System.currentTimeMillis() - old.at < STALE_MS) return@withContext old
        val loc = if (hasLocationPermission()) currentLocation() else null
        when {
            loc != null && old != null && !force && distance(old.lat, old.lon, loc) < MOVE_METERS && System.currentTimeMillis() - old.at < STALE_MS -> old
            loc != null -> fetch(loc.latitude, loc.longitude)
            old != null -> old
            else -> null
        }
    }

    /** 定位:Fused balanced;系统里没有 GMS 或超时就退回网络最后位置。 */
    private suspend fun currentLocation(): Location? {
        val fused = runCatching {
            LocationServices.getFusedLocationProviderClient(context)
        }.getOrNull() ?: return lastKnown()
        return runCatching {
            Tasks.await(fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token), 10_000L, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: lastKnown()
    }

    private fun lastKnown(): Location? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
    }.getOrNull()

    /** 地名:小区/区 > 城 > 县 > 省,加上国家码,像 "Lachhmangarh Tehsil, IN"。 */
    private fun geocode(lat: Double, lon: Double): String {
        val g = runCatching { Geocoder(context, Locale.getDefault()) }.getOrNull() ?: return ""
        if (!Geocoder.isPresent()) return ""
        val addr = runCatching { g.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull() ?: return ""
        val name = addr.subAdminArea ?: addr.locality ?: addr.subLocality ?: addr.adminArea ?: addr.countryName.orEmpty()
        val cc = addr.countryCode
        return when {
            name.isBlank() -> ""
            cc.isNullOrBlank() -> name
            else -> "$name, $cc"
        }
    }

    private suspend fun fetch(lat: Double, lon: Double): WeatherData? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=sunrise,sunset&forecast_days=2&timezone=auto"
        val text = get(url) ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val cur = root.optJSONObject("current") ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return null
        val code = cur.optInt("weather_code", 0)
        val isDay = cur.optInt("is_day", 1) == 1
        val daily = root.optJSONObject("daily")
        fun arr(key: String): List<String>? = daily?.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it, "") } }
        val sunrises = arr("sunrise")
        val sunsets = arr("sunset")
        val data = WeatherData(
            place = geocode(lat, lon),
            temp = temp, code = code, isDay = isDay,
            sunset = sunsets?.getOrNull(0)?.takeIf { it.isNotBlank() },
            nextSunrise = if (sunrises != null && sunrises.size > 1) sunrises[1] else sunrises?.getOrNull(0),
            todaySunrise = sunrises?.getOrNull(0)?.takeIf { it.isNotBlank() },
            lat = lat, lon = lon, at = System.currentTimeMillis(),
        )
        saveCache(data)
        return data
    }

    private suspend fun saveCache(d: WeatherData) {
        val o = JSONObject()
            .put("place", d.place).put("temp", d.temp).put("code", d.code)
            .put("isDay", d.isDay).put("sunset", d.sunset.orEmpty()).put("nextSunrise", d.nextSunrise.orEmpty())
            .put("todaySunrise", d.todaySunrise.orEmpty())
            .put("lat", d.lat).put("lon", d.lon).put("at", d.at)
        context.homeWeatherStore.edit { it[WEATHER_JSON] = o.toString() }
    }

    private suspend fun readCache(): WeatherData? {
        val raw = context.homeWeatherStore.data.first()[WEATHER_JSON] ?: return null
        return runCatching {
            val o = JSONObject(raw)
            WeatherData(
                place = o.optString("place"), temp = o.getDouble("temp"), code = o.getInt("code"),
                isDay = o.getBoolean("isDay"), sunset = o.optString("sunset").ifBlank { null },
                nextSunrise = o.optString("nextSunrise").ifBlank { null },
                todaySunrise = o.optString("todaySunrise").ifBlank { null },
                lat = o.getDouble("lat"), lon = o.getDouble("lon"), at = o.getLong("at"),
            )
        }.getOrNull()
    }

    private fun distance(lat1: Double, lon1: Double, l2: Location): Float {
        val res = FloatArray(1)
        Location.distanceBetween(lat1, lon1, l2.latitude, l2.longitude, res)
        return res[0]
    }

    private fun get(url: String): String? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        c.inputStream.bufferedReader().use { it.readText() }
    }.onFailure { it.printStackTrace() }.getOrNull()

    companion object {
        /** 30 分钟内不重取。 */
        const val STALE_MS = 30 * 60_000L
        /** 定位挪过这么远就立刻重取。 */
        const val MOVE_METERS = 5_000f
    }
}

/** 日落/日出现在属于哪段:日出前和日落后都显示日出,白天显示日落。 */
enum class SunPhase { Sunset, Sunrise }

fun sunPhase(w: WeatherData, now: LocalDateTime): SunPhase {
    val rise = w.todaySunrise?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val set = w.sunset?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    return when {
        rise != null && now < rise -> SunPhase.Sunrise
        set != null && now < set -> SunPhase.Sunset
        else -> SunPhase.Sunrise
    }
}
```

## FILE: app/src/main/java/com/meenai/mina/home/NotificationRepository.kt

```kotlin
package com.meenai.mina.home

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.meenai.mina.R
import com.meenai.mina.cap.phone.shell.NotificationStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Home 顶部的重要通知:来自 [NotificationStore](通知使用权收进来的最近通知,内存),
 * 左滑删掉的那几条记进 DataStore,不再回来;Undo 把它们放回来。
 */
data class NotifUi(
    val key: String,
    val app: String?,
    val title: String,
    val text: String,
    /** 动词小胶囊的文字资源。 */
    val verb: Int,
)

private val Context.notifDismissedStore by preferencesDataStore(name = "notif_dismissed")
private val DISMISSED_KEY = stringSetPreferencesKey("dismissed_notifs")

class NotificationRepository(private val context: Context) {

    /** 内存里也留一份,删了立刻消失、Undo 立刻回来,不用等 DataStore。 */
    private val dismissed = LinkedHashSet<String>()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        dismissed.addAll(context.notifDismissedStore.data.first()[DISMISSED_KEY].orEmpty())
    }

    /** 当前要展示的通知(最新在前),已删的滤掉。 */
    suspend fun active(limit: Int = 6): List<NotifUi> {
        ensureLoaded()
        return NotificationStore.snapshot(null, 20)
            .filter { o -> (jsonKey(o) !in dismissed) }
            .take(limit)
            .map { o ->
                val title = jsonText(o, "title")
                NotifUi(
                    key = jsonKey(o),
                    app = jsonText(o, "app").takeIf { it.isNotBlank() },
                    title = title.ifBlank { jsonText(o, "text") },
                    text = jsonText(o, "text"),
                    verb = R.string.home_note_verb,
                )
            }
            .filter { it.title.isNotBlank() }
    }

    suspend fun dismiss(key: String) {
        ensureLoaded()
        dismissed.add(key)
        persist()
    }

    suspend fun undismiss(key: String) {
        ensureLoaded()
        dismissed.remove(key)
        persist()
    }

    private suspend fun persist() {
        context.notifDismissedStore.edit { it[DISMISSED_KEY] = dismissed.toSet() }
    }

    private fun jsonKey(o: JsonObject): String = (o["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun jsonText(o: JsonObject, k: String): String = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
```

## FILE: app/src/main/java/com/meenai/mina/home/HomeViewModel.kt

```kotlin
package com.meenai.mina.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home 页的状态:顶部通知(可左滑删除、可撤销)、常驻天气卡(自动定位)、天气是不是第一次在取。
 * 通知库没有流,隔几秒看一眼;天气启动先把缓存画出来再原地更新,打开期间每半小时一刷。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    data class HomeUiState(
        val notifications: List<NotifUi> = emptyList(),
        val weather: WeatherData? = null,
        val isWeatherLoading: Boolean = false,
        /** 没给定位权限时亮出「开启定位」的入口。 */
        val locationDenied: Boolean = false,
    )

    private val weatherRepo = WeatherRepository(app)
    private val notifRepo = NotificationRepository(app)
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        // 启动:缓存先上屏,新鲜度判断决定要不要重取
        viewModelScope.launch {
            weatherRepo.cached()?.let { c -> _ui.update { it.copy(weather = c) } }
            refreshWeather(force = false)
        }
        // 打开期间每 30 分钟一刷
        viewModelScope.launch {
            while (true) {
                delay(30 * 60_000L)
                refreshWeather(force = false)
            }
        }
        // 通知:隔几秒看一眼(删掉的不回来,Undo 才回来)
        viewModelScope.launch {
            while (true) {
                _ui.update { it.copy(notifications = notifRepo.active()) }
                delay(4_000L)
            }
        }
    }

    /** 回到前台:位置可能变了,看一眼(缓存新鲜就原地不动)。 */
    fun onResume() = refreshWeather(force = false)

    /** 定位授权刚给:立刻强制刷新一次。 */
    fun onLocationGranted() = refreshWeather(force = true)

    private fun refreshWeather(force: Boolean) = viewModelScope.launch {
        _ui.update { it.copy(isWeatherLoading = true) }
        val w = runCatching { weatherRepo.refresh(force) }.getOrNull()
        _ui.update {
            it.copy(
                weather = w ?: it.weather,
                isWeatherLoading = false,
                locationDenied = !weatherRepo.hasLocationPermission(),
            )
        }
    }

    /** 删一条通知:界面上立刻消失,持久化里也删掉(不会回来)。 */
    fun dismiss(key: String) {
        _ui.update { it.copy(notifications = it.notifications.filterNot { n -> n.key == key }) }
        viewModelScope.launch { notifRepo.dismiss(key) }
    }

    /** Undo:放回来。 */
    fun undoDismiss(key: String) = viewModelScope.launch {
        notifRepo.undismiss(key)
        _ui.update { it.copy(notifications = notifRepo.active()) }
    }
}
```

## FILE: app/src/main/java/com/meenai/mina/ui/components/HomeCards.kt

```kotlin
package com.meenai.mina.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaMotion
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Home 各卡的公共玻璃底座:28dp 圆角毛玻璃、1dp 描边,直接用 [MinaCard]。
 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    MinaCard(modifier, content = content)

/**
 * 左滑删除的卡片:卡片跟着手指走,轻微左倾(最多 3 度)并缩到 0.98,
 * 背后右侧的垃圾桶随进度淡入放大;过了 40% 宽度或快速一甩就滑出去删掉,否则弹回原位。
 * 过线的瞬间来一下轻触感。
 */
@Composable
fun SwipeToDeleteCard(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = MinaTheme.colors
    val haptic = LocalHapticFeedback.current
    val reduceMotion = MinaTheme.reduceMotion
    val scope = rememberCoroutineScope()
    var cardW by remember { mutableStateOf(1) }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var crossed by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().onSizeChanged { cardW = it.width.coerceAtLeast(1) }) {
        // 卡片背后右侧的垃圾桶
        Box(Modifier.matchParentSize().clip(MinaShapes.Card)) {
            val p = ((-offset.value) / (cardW * 0.4f)).coerceIn(0f, 1f)
            Icon(
                MinaIcons.Trash, null,
                Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        alpha = 0.12f + 0.88f * p
                        val s = 0.8f + 0.4f * p
                        scaleX = s; scaleY = s
                    }
                    .padding(end = 26.dp)
                    .size(24.dp),
                tint = c.bad,
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.value
                    if (!reduceMotion) {
                        val p = ((-offset.value) / (cardW * 0.4f)).coerceIn(0f, 1f)
                        rotationZ = -3f * p
                        val s = 1f - 0.02f * p
                        scaleX = s; scaleY = s
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { crossed = false },
                        onHorizontalDrag = { change, drag ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val threshold = cardW * 0.4f
                            val target = (offset.value + drag).coerceAtMost(0f)
                            scope.launch { offset.snapTo(target) }
                            if (!crossed && target <= -threshold) {
                                crossed = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (crossed && target > -threshold) {
                                crossed = false
                            }
                        },
                        onDragEnd = {
                            val threshold = cardW * 0.4f
                            val velocity = tracker.calculateVelocity().x
                            val past = offset.value <= -threshold || velocity < -1500f
                            if (past) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                scope.launch {
                                    offset.animateTo(-cardW * 1.35f, tween(220))
                                    onDelete()
                                }
                            } else {
                                scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) } },
                    )
                },
            content = content,
        )
    }
}

/**
 * 顶部通知卡:一小句话 —— 动词小胶囊 + 正文 + 内联的小圆 App 图标,[FlowRow] 自然换行;整卡可点。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationCard(
    verb: String,
    title: String,
    app: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val c = MinaTheme.colors
    val icon = remember(app) { app?.let { resolveAppIcon(context, it) } }
    GlassCard(modifier.pressScale(onClick)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    .clip(MinaShapes.Pill)
                    .background(c.fill)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(verb, style = MinaType.Caption, color = c.ink)
            }
            Text(
                title,
                Modifier.align(Alignment.CenterVertically),
                style = MinaType.Body,
                color = c.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (icon != null) {
                Image(
                    icon.asImageBitmap(), null,
                    Modifier
                        .align(Alignment.CenterVertically)
                        .size(18.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

private fun resolveAppIcon(context: Context, pkg: String): Bitmap? = runCatching {
    context.packageManager.getApplicationIcon(pkg).toBitmap(48, 48)
}.getOrNull()

/** 天气卡在界面上长什么样(纯展示,文字在上层取好)。 */
data class WeatherCardModel(
    val place: String?,
    val condition: String,
    val temp: String,
    val sunLabel: String,
    val sunTime: String,
    val icon: ImageVector,
)

/**
 * 常驻天气卡:顶行是天气图标 + 地名;里面两块白 70% 圆角 20dp 的小瓷砖,
 * 左边天气 + 大号温度,右边日落/日出 + 时刻;数值和图标原地淡换,第一次才闪一下。
 */
@Composable
fun WeatherCardView(
    w: WeatherCardModel?,
    loading: Boolean,
    showEnableLocation: Boolean,
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier,
    rationale: String? = null,
) {
    val c = MinaTheme.colors
    GlassCard(modifier) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedContent(w?.icon ?: MinaIcons.Cloud, label = "weatherIcon") { ic ->
                        Icon(ic, null, Modifier.size(20.dp), tint = c.ink2)
                    }
                    Text(
                        w?.place.orEmpty(),
                        Modifier.weight(1f),
                        style = MinaType.BodyStrong,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showEnableLocation) {
                        Box(
                            Modifier
                                .clip(MinaShapes.Pill)
                                .background(c.btnPrimary)
                                .pressScale(onEnableLocation)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                com.meenai.mina.i18n.tr(com.meenai.mina.R.string.home_enable_location),
                                style = MinaType.Caption,
                                color = c.onBtnPrimary,
                            )
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WeatherTile(Modifier.weight(1f)) {
                        AnimatedContent(w?.condition.orEmpty(), label = "cond") { label ->
                            Text(label, style = MinaType.Body.copy(fontSize = 15.sp), color = c.ink3)
                        }
                        AnimatedContent(w?.temp.orEmpty(), label = "temp") { t ->
                            Text(t, style = MinaType.LargeTitle.copy(fontSize = 36.sp), color = c.ink)
                        }
                    }
                    WeatherTile(Modifier.weight(1f)) {
                        AnimatedContent(w?.sunLabel.orEmpty(), label = "sunLabel") { l ->
                            Text(l, style = MinaType.Body.copy(fontSize = 15.sp), color = c.ink3)
                        }
                        AnimatedContent(w?.sunTime.orEmpty(), label = "sunTime") { t ->
                            Text(t, style = MinaType.Title.copy(fontSize = 22.sp), color = c.ink)
                        }
                    }
                }
                if (rationale != null) {
                    Text(rationale, Modifier.padding(top = 8.dp), style = MinaType.Hint, color = c.ink3)
                }
            }
            if (loading && w == null) ShimmerOverlay(Modifier.fillMaxSize())
        }
    }
}

/** 白 70% 的小瓷砖(深色下用主题底色),圆角 20dp、内边距 16dp。 */
@Composable
fun WeatherTile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = MinaTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (c.isDark) c.fill else Color.White.copy(alpha = 0.70f))
            .padding(16.dp),
        content = content,
    )
}

/** 第一次还没数据时的一层细闪。 */
@Composable
private fun ShimmerOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shimmerX",
    )
    Box(
        modifier.clip(MinaShapes.Card).drawWithContent {
            drawContent()
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.30f), Color.Transparent),
                    start = Offset(size.width * (x * 2f - 1f), 0f),
                    end = Offset(size.width * (x * 2f), size.height),
                )
            )
        }
    )
}

/** 新通知进场:淡入 + 向下滑进来。 */
fun Modifier.appearDown(): Modifier = composed {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    if (MinaTheme.reduceMotion) return@composed this
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(240, easing = MinaMotion.Decelerate), label = "appearAlpha")
    val ty by animateFloatAsState(if (shown) 0f else -16.dp.value, tween(300, easing = MinaMotion.Decelerate), label = "appearY")
    graphicsLayer { alpha = a; translationY = ty }
}

/** WMO 天气代码选图标:0 晴(白天太阳/夜里月亮),雨系用雨,雾用雾,雷用闪电,其余都算云。 */
fun weatherIconFor(code: Int?, isDay: Boolean): ImageVector = when {
    code == 0 -> if (isDay) MinaIcons.Sun else MinaIcons.Moon
    code in 51..57 || code in 61..67 || code in 80..82 -> MinaIcons.Rain
    code == 45 || code == 48 -> MinaIcons.Fog
    code in 95..99 -> MinaIcons.Bolt
    else -> MinaIcons.Cloud
}

// ---------- 预览 ----------

@Preview(name = "Notification card", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun NotificationCardPreview() {
    MinaTheme(dark = false) {
        NotificationCard(verb = "Show", title = "Priya: running late, see you in 10", app = null, Modifier.padding(8.dp))
    }
}

@Preview(name = "Weather (day)", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun WeatherDayPreview() {
    MinaTheme(dark = false) {
        WeatherCardView(
            WeatherCardModel("Lachhmangarh Tehsil, IN", "Clear Sky", "30°C", "Sunset", "5:34 PM", MinaIcons.Sun),
            loading = false, showEnableLocation = false, onEnableLocation = {},
            Modifier.padding(8.dp),
        )
    }
}

@Preview(name = "Weather (after sunset)", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun WeatherNightPreview() {
    MinaTheme(dark = false) {
        WeatherCardView(
            WeatherCardModel("Lachhmangarh Tehsil, IN", "Fog", "18°C", "Sunrise", "6:12 AM", MinaIcons.Moon),
            loading = false, showEnableLocation = false, onEnableLocation = {},
            Modifier.padding(8.dp),
        )
    }
}

@Preview(name = "Empty notifications state", showBackground = true, backgroundColor = 0xFFA9B6C8L, widthDp = 400)
@Composable
private fun EmptyNotificationsPreview() {
    MinaTheme(dark = false) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            WeatherCardView(
                WeatherCardModel("Lachhmangarh Tehsil, IN", "Clear Sky", "30°C", "Sunset", "5:34 PM", MinaIcons.Sun),
                loading = false, showEnableLocation = false, onEnableLocation = {},
            )
        }
    }
}

/**
 * 顶栏的紧凑分段:56dp 高、玻璃底(白 35~45% 罩在 Haze 模糊上,1dp 白 30% 描边),
 * 每项内容定宽(文字部分最少 88dp,左右各 22dp 内边距),
 * 黑色滑块随弹簧滑动、宽度跟着标签走,文字 200ms 渐换色,切换带轻触感,每项按压 0.97。
 */
@Composable
fun GlassCompactTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MinaTheme.colors
    val haze = LocalHazeState.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val tabWidths = remember(options) {
        options.map { label ->
            val text = measurer.measure(AnnotatedString(label), style, maxLines = 1, softWrap = false).size.width
            text.coerceAtLeast(with(density) { 44.dp.roundToPx() }) + with(density) { 44.dp.roundToPx() }
        }
    }
    val height = 56.dp
    // haze 0.9 画玻璃时要给 backgroundColor(v0.1.61 的崩溃就是它没给)
    val glassBase = if (c.isDark) GlassBaseDark else GlassBaseLight
    val glassStyle = remember(c) { HazeStyle(backgroundColor = glassBase, tint = HazeTint(c.surface), blurRadius = 28.dp) }
    Box(
        modifier
            .height(height)
            .clip(MinaShapes.Pill)
            .then(
                if (haze != null && GlassBlurSupported) {
                    Modifier.hazeChild(haze, shape = MinaShapes.Pill, style = glassStyle)
                } else {
                    Modifier.background(c.glass.copy(alpha = 0.42f))
                }
            )
            .border(1.dp, Color.White.copy(alpha = 0.30f), MinaShapes.Pill)
    ) {
        val offX by animateDpAsState(
            with(density) { (tabWidths.take(selected).sum() + 4.dp.toPx()).toDp() },
            spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "pillX",
        )
        val pillW by animateDpAsState(
            with(density) { tabWidths[selected].toDp() },
            spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "pillW",
        )
        Box(
            Modifier
                .offset(x = offX)
                .width(pillW)
                .height(height)
                .padding(vertical = 4.dp)
                .clip(MinaShapes.Pill)
                .background(c.btnPrimary)
        )
        Row(Modifier.padding(horizontal = 4.dp)) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .width(with(density) { tabWidths[i].toDp() })
                        .height(height)
                        .pressScale(onClick = {
                            if (i != selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(i)
                            }
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    val color by animateColorAsState(if (i == selected) c.onBtnPrimary else c.ink2, tween(200), label = "tabColor")
                    Text(label, style = style, color = color, maxLines = 1)
                }
            }
        }
    }
}
```

## FILE: app/src/main/java/com/meenai/mina/ui/HomeScreen.kt

```kotlin
package com.meenai.mina.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meenai.mina.R
import com.meenai.mina.home.HomePanel
import com.meenai.mina.home.HomeViewModel
import com.meenai.mina.home.PanelsStore
import com.meenai.mina.home.SunPhase
import com.meenai.mina.home.Weather
import com.meenai.mina.home.WeatherData
import com.meenai.mina.home.sunPhase
import com.meenai.mina.home.weatherKind
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.components.BarIconButton
import com.meenai.mina.ui.components.GlassCompactTabs
import com.meenai.mina.ui.components.GlassPage
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.MinaCard
import com.meenai.mina.ui.components.NotificationCard
import com.meenai.mina.ui.components.SwipeToDeleteCard
import com.meenai.mina.ui.components.SwipeToDeleteRow
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.components.WeatherCardModel
import com.meenai.mina.ui.components.WeatherCardView
import com.meenai.mina.ui.components.appearDown
import com.meenai.mina.ui.components.staggerIn
import com.meenai.mina.ui.components.weatherIconFor
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Home 页:顶部是可左滑删除的通知卡,然后常驻天气卡(自动定位、open-meteo),
 * 再往下的面板(agent 在盯的事)原样保留。原来的「Your apps」一段已撤。
 */
@Composable
fun HomeScreen(
    openDrawer: () -> Unit,
    onSettings: () -> Unit,
    pill: @Composable () -> Unit,
    updateAvailable: Boolean,
    onOpenAbout: () -> Unit,
    phoneSetupNeeded: Boolean,
    onPhoneSetup: () -> Unit,
    /** 把一句话交给 agent 去办(发进当前对话,并回到 Chat 页看回复)。 */
    onAskAgent: (String) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 回到前台时看一眼天气(半小时内的缓存直接用,位置挪远了才重取)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResume() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val staggerBase = (if (updateAvailable) 1 else 0) + (if (phoneSetupNeeded) 1 else 0)

    GlassPage {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    "", left = MinaIcons.Menu, leftLabel = stringResource(R.string.chat_menu), onLeft = openDrawer,
                    center = pill,
                    right = { BarIconButton(MinaIcons.Gear, stringResource(R.string.settings_title), onSettings) },
                )
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (updateAvailable) item(key = "sys-update") {
                        MinaCard(Modifier.staggerIn(0, 16.dp)) {
                            ActionCard(tr(R.string.home_update_verb), tr(R.string.home_update_title), tr(R.string.home_update_sub), first = true, onClick = onOpenAbout)
                        }
                    }
                    if (phoneSetupNeeded) item(key = "sys-phone") {
                        MinaCard(Modifier.staggerIn(if (updateAvailable) 1 else 0, 16.dp)) {
                            ActionCard(tr(R.string.home_fix_verb), tr(R.string.home_phone_title), tr(R.string.home_phone_sub), first = true, onClick = onPhoneSetup)
                        }
                    }
                    items(state.notifications, key = { "notif-" + it.key }) { n ->
                        Box(Modifier.animateItem().appearDown()) {
                            SwipeToDeleteCard(onDelete = {
                                vm.dismiss(n.key)
                                scope.launch {
                                    val r = snackbar.showSnackbar(
                                        tr(R.string.home_deleted),
                                        actionLabel = tr(R.string.home_undo),
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (r == SnackbarResult.ActionPerformed) vm.undoDismiss(n.key)
                                }
                            }) {
                                NotificationCard(
                                    verb = tr(n.verb),
                                    title = n.title,
                                    app = n.app,
                                    onClick = { openRelatedApp(context, n.app) },
                                )
                            }
                        }
                    }
                    item(key = "weather") { WeatherSection(vm, state.weather, state.isWeatherLoading, state.locationDenied, staggerBase) }
                    item(key = "panels") { PanelsSection(onAskAgent, staggerBase + 1) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

/** 顶栏中间的 Home/Chat 分段胶囊:56dp 高、内容定宽,黑色滑块随弹簧滑动。 */
@Composable
fun RootTabPill(home: Boolean, onPick: (Boolean) -> Unit) {
    GlassCompactTabs(
        options = listOf(tr(R.string.root_home), tr(R.string.root_chat)),
        selected = if (home) 0 else 1,
        onSelect = { i -> onPick(i == 0) },
    )
}

// ---------- 常驻天气卡 ----------

@Composable
private fun WeatherSection(
    vm: HomeViewModel,
    weather: WeatherData?,
    loading: Boolean,
    locationDenied: Boolean,
    staggerIndex: Int,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) vm.onLocationGranted()
    }
    val model = weather?.let { weatherCardModel(it, context) }
    MinaCard(Modifier.staggerIn(staggerIndex, 16.dp)) {
        WeatherCardView(
            w = model,
            loading = loading,
            showEnableLocation = locationDenied,
            onEnableLocation = {
                launcher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            },
            rationale = if (locationDenied) tr(R.string.home_location_rationale) else null,
        )
    }
}

/** 天气数据 → 界面要的文字:天气名、温度、日落/下一次日出(按设备 12/24 小时制)。 */
private fun weatherCardModel(w: WeatherData, context: Context): WeatherCardModel {
    val now = LocalDateTime.now()
    val phase = sunPhase(w, now)
    val riseToday = w.todaySunrise?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val iso = when (phase) {
        SunPhase.Sunset -> w.sunset
        SunPhase.Sunrise -> if (riseToday != null && now < riseToday) w.todaySunrise else w.nextSunrise
    }
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    val fmt = DateTimeFormatter.ofPattern(pattern)
    return WeatherCardModel(
        place = w.place.ifBlank { null },
        condition = tr(weatherKind(w.code)),
        temp = "${w.temp.roundToInt()}°C",
        sunLabel = tr(if (phase == SunPhase.Sunset) R.string.panel_sunset else R.string.home_sunrise),
        sunTime = iso?.let { i -> runCatching { fmt.format(LocalDateTime.parse(i)) }.getOrNull() } ?: "—",
        icon = weatherIconFor(w.code, w.isDay),
    )
}

/** 点卡片打开通知来源的 App;没有可打开的就什么都不做。 */
private fun openRelatedApp(context: Context, pkg: String?) {
    if (pkg.isNullOrBlank()) return
    runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }
}

/** 顶栏下面的一张状态卡:动词胶囊 + 标题 + 说明,整卡可点。 */
@Composable
private fun ActionCard(verb: String, title: String, subtitle: String, first: Boolean, onClick: (() -> Unit)? = null) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(verb, style = MinaType.Caption, color = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MinaType.Body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- 面板 ----------

@Composable
private fun PanelsSection(onAskAgent: (String) -> Unit, staggerIndex: Int = 0) {
    val context = LocalContext.current
    val store = remember { PanelsStore.shared(context) }
    val scope = rememberCoroutineScope()
    var panels by remember { mutableStateOf<List<HomePanel>>(emptyList()) }
    var create by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        panels = withContext(Dispatchers.IO) { store.all() }
        // 天气卡在打开时刷新:半小时内的旧数据直接用
        val stale = panels.filter { it.kind == "weather" && it.lat != null && it.lon != null && (it.temp == null || it.updatedAt < System.currentTimeMillis() - 30 * 60_000L) }
        if (stale.isNotEmpty()) {
            val fresh = stale.mapNotNull { p ->
                val now = withContext(Dispatchers.IO) { Weather.now(p.lat!!, p.lon!!) } ?: return@mapNotNull null
                p.copy(temp = now.temp, code = now.code, sunset = now.sunset, updatedAt = System.currentTimeMillis())
            }
            fresh.forEach { withContext(Dispatchers.IO) { store.put(it) } }
            panels = withContext(Dispatchers.IO) { store.all() }
        }
    }

    panels.forEach { p ->
        val del: () -> Unit = { scope.launch { withContext(Dispatchers.IO) { store.remove(p.id) }; panels = store.all() } }
        SwipeToDeleteRow(onDelete = del) {
            if (p.kind == "weather") WeatherCard(p, onDelete = del)
            else TrackCard(p, onAsk = { onAskAgent(tr(R.string.panel_ask_msg, p.name)) }, onDelete = del)
        }
    }
    GroupCard(Modifier.staggerIn(staggerIndex, 16.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { create = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(MinaIcons.Plus, null, Modifier.size(20.dp), tint = MinaTheme.colors.ink)
            Text(tr(R.string.panel_create), style = MinaType.BodyStrong, color = MinaTheme.colors.ink)
        }
    }
    if (create) PanelCreateDialog(
        onDismiss = { create = false; failed = false },
        onCreate = { kind, text ->
            scope.launch {
                if (kind == "weather") {
                    val place = withContext(Dispatchers.IO) { Weather.locate(text) }
                    if (place == null) { failed = true; return@launch }
                    val now = withContext(Dispatchers.IO) { Weather.now(place.first, place.second) }
                    val panel = HomePanel(
                        id = "w${System.currentTimeMillis()}", kind = "weather", name = text.trim(),
                        lat = place.first, lon = place.second,
                        temp = now?.temp, code = now?.code, sunset = now?.sunset, updatedAt = System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.IO) { store.put(panel) }
                } else {
                    withContext(Dispatchers.IO) { store.put(HomePanel("t${System.currentTimeMillis()}", "track", text.trim(), updatedAt = System.currentTimeMillis())) }
                }
                panels = withContext(Dispatchers.IO) { store.all() }
                create = false
            }
        },
        failed = failed,
    )
}

@Composable
private fun WeatherCard(p: HomePanel, onDelete: () -> Unit) {
    val c = MinaTheme.colors
    MinaCard(Modifier.padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MinaIcons.Sun, null, Modifier.size(18.dp), tint = c.ink2)
            Text(p.name + (p.country?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""), Modifier.weight(1f).padding(start = 8.dp), style = MinaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BarIconButton(MinaIcons.Trash, "", onDelete)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PanelTile(Modifier.weight(1f), tr(weatherKind(p.code)), if (p.temp != null) "${p.temp.toInt()}°C" else "—")
            PanelTile(Modifier.weight(1f), tr(R.string.panel_sunset), p.sunset?.takeIf { it.isNotBlank() }?.split("T")?.lastOrNull()?.take(5) ?: "—")
        }
    }
}

@Composable
private fun PanelTile(modifier: Modifier = Modifier, label: String, value: String) {
    val c = MinaTheme.colors
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MinaType.Caption, color = c.ink3)
        Text(value, style = MinaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrackCard(p: HomePanel, onAsk: () -> Unit, onDelete: () -> Unit) {
    val c = MinaTheme.colors
    MinaCard(Modifier.padding(bottom = 12.dp).clickable(onClick = onAsk)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(tr(R.string.panel_tracking), style = MinaType.Caption, color = c.ink)
            }
            Text(p.name, Modifier.weight(1f).padding(start = 10.dp), style = MinaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BarIconButton(MinaIcons.Trash, "", onDelete)
        }
        Text(
            tr(R.string.panel_updated, panelTime(p.updatedAt)),
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MinaType.Caption, color = c.ink3,
        )
    }
}

private fun panelTime(atMs: Long): String =
    if (atMs <= 0) "" else DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(atMs))

@Composable
private fun PanelCreateDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit, failed: Boolean) {
    val c = MinaTheme.colors
    var kind by remember { mutableStateOf("weather") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.panel_new_title), style = MinaType.Title, color = c.ink) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KindChip(tr(R.string.panel_kind_weather), kind == "weather") { kind = "weather" }
                    KindChip(tr(R.string.panel_kind_track), kind == "track") { kind = "track" }
                }
                BasicTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = c.ink),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                        .clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        Box { if (text.isEmpty()) Text(if (kind == "weather") tr(R.string.panel_city_hint) else tr(R.string.panel_topic_hint), style = MinaType.Body, color = c.ink3); inner }
                    },
                )
                if (failed) Text(tr(R.string.panel_weather_fail), Modifier.padding(top = 8.dp), style = MinaType.Caption, color = c.bad)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onCreate(kind, text) }) { Text(tr(R.string.panel_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun KindChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = MinaTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.ink else c.fill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MinaType.Caption, color = if (selected) c.bg else c.ink)
    }
}
```

## build.gradle.kts (diff summary: added deps: DataStore 1.1.1, play-services-location 21.3.0, lifecycle-viewmodel/runtime-compose 2.8.7, ui-tooling-preview (+debugImplementation ui-tooling))

## AndroidManifest.xml (diff summary: added ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION)

## Glass.kt diff (diff summary: staggerIn(index, slide: Dp = 26.dp) gained an optional slide param)

## Icons.kt diff (diff summary: added Cloud/Rain/Fog/Bolt weather icons)

## strings diff (diff summary: added 6 keys (home_sunrise, home_enable_location, home_location_rationale, home_deleted, home_undo, home_no_location); same keys in values-hi)
