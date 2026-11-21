package com.originpods.app.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.originpods.app.BuildConfig
import com.originpods.app.hook.OriginPodsXposedEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class LogLevel(val shortLabel: String, val priority: Int) {
    DEBUG("D", Log.DEBUG),
    INFO("I", Log.INFO),
    WARN("W", Log.WARN),
    ERROR("E", Log.ERROR),
    ;

    companion object {
        fun fromPriority(priority: Int): LogLevel = when {
            priority >= Log.ERROR -> ERROR
            priority == Log.WARN -> WARN
            priority == Log.INFO -> INFO
            else -> DEBUG
        }

        fun fromChar(c: Char): LogLevel = when (c.uppercaseChar()) {
            'E', 'F', 'A' -> ERROR
            'W' -> WARN
            'I' -> INFO
            else -> DEBUG
        }
    }
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val source: String = "App",
) {
    fun formatLine(): String = "$timestamp ${level.shortLabel}/$tag [$source]: $message"
}

/**
 * Collects OriginPods logs from cross-process broadcasts, local persisted events,
 * and on-demand system `logcat` capture (with Root fallback to app-local logcat).
 */
object OriginLogStore {
    private const val MAX_ENTRIES = 1500
    private const val LOG_FILE_NAME = "originpods-events.log"
    private val LOGCAT_LINE_REGEX = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEFA])/([^\s(]+)\s*\(\s*\d+\):\s*(.*)$""",
    )

    private val idGen = AtomicLong(1L)
    private val lock = Any()
    private val localEvents = ArrayList<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val _lastCaptureSource = MutableStateFlow("本地事件缓存")
    val lastCaptureSource: StateFlow<String> = _lastCaptureSource.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val appCtx = context.applicationContext
            loadPersistedEvents(appCtx)
            registerLogReceiver(appCtx)
            appendInternal(
                appCtx,
                LogLevel.INFO,
                "OriginPods.UI",
                "OriginPods v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) initialized",
                "App",
            )
        }
    }

    fun append(
        context: Context?,
        level: LogLevel,
        tag: String,
        message: String,
        source: String = "App",
    ) {
        appendInternal(context?.applicationContext, level, tag, message, source)
    }

    suspend fun captureSystemLogs(context: Context): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val tagsFilter =
            "OriginPods:V OriginPods.BT:V OriginPods.CC:V OriginPods.Settings:V " +
                "OriginPods.VivoTws:V OriginPods.Bridge:V OriginPods.Session:V " +
                "OriginPods.ControlApp:V AndroidRuntime:E *:S"

        val rootLines = runShellLogcat(
            arrayOf("su", "-c", "logcat -d -v time -t 1000 $tagsFilter"),
        )
        val (captured, sourceLabel) = if (rootLines != null) {
            parseLogcatLines(rootLines, "Logcat(Root)") to "系统全局 Logcat (Root)"
        } else {
            val localLines = runShellLogcat(
                arrayOf("logcat", "-d", "-v", "time", "-t", "400"),
            ).orEmpty()
            val filtered = parseLogcatLines(localLines, "Logcat")
                .filter { it.tag.startsWith("OriginPods") || it.level == LogLevel.ERROR }
            filtered to "应用进程 Logcat + 广播缓存"
        }

        synchronized(lock) {
            val merged = (localEvents + captured)
                .distinctBy { "${it.timestamp}|${it.level}|${it.tag}|${it.message}" }
                .takeLast(MAX_ENTRIES)
            _entries.value = merged
            _lastCaptureSource.value = sourceLabel
        }
        sourceLabel
    }

    fun clear(context: Context) {
        synchronized(lock) {
            localEvents.clear()
            _entries.value = emptyList()
            runCatching { File(context.applicationContext.filesDir, LOG_FILE_NAME).delete() }
        }
    }

    fun buildExportText(context: Context, items: List<LogEntry>): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== OriginPods Diagnostic Logs ===")
        sb.appendLine("Exported: $now")
        sb.appendLine("App: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        sb.appendLine("Device: ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Source: ${_lastCaptureSource.value}")
        sb.appendLine("Entries: ${items.size}")
        sb.appendLine("==================================")
        items.forEach { sb.appendLine(it.formatLine()) }
        return sb.toString()
    }

    private fun registerLogReceiver(appCtx: Context) {
        runCatching {
            val filter = IntentFilter(OriginPodsXposedEntry.ACTION_LOG)
            appCtx.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val priority = intent.getIntExtra("priority", Log.INFO)
                        val tag = intent.getStringExtra("tag") ?: "OriginPods"
                        val message = intent.getStringExtra("message") ?: return
                        val source = intent.getStringExtra("source") ?: "Hook"
                        appendInternal(appCtx, LogLevel.fromPriority(priority), tag, message, source)
                    }
                },
                filter,
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    private fun appendInternal(
        appCtx: Context?,
        level: LogLevel,
        tag: String,
        message: String,
        source: String,
    ) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = LogEntry(
            id = idGen.getAndIncrement(),
            timestamp = ts,
            level = level,
            tag = tag,
            message = message,
            source = source,
        )
        synchronized(lock) {
            localEvents.add(entry)
            if (localEvents.size > MAX_ENTRIES) {
                localEvents.removeAt(0)
            }
            val current = _entries.value.toMutableList()
            current.add(entry)
            if (current.size > MAX_ENTRIES) {
                _entries.value = current.takeLast(MAX_ENTRIES)
            } else {
                _entries.value = current
            }
            if (appCtx != null) {
                persistLine(appCtx, entry.formatLine())
            }
        }
    }

    private fun persistLine(appCtx: Context, line: String) {
        runCatching {
            val file = File(appCtx.filesDir, LOG_FILE_NAME)
            if (file.exists() && file.length() > 512 * 1024L) {
                val tail = file.readLines(Charsets.UTF_8).takeLast(800)
                file.writeText(tail.joinToString("\n") + "\n", Charsets.UTF_8)
            }
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    private fun loadPersistedEvents(appCtx: Context) {
        val file = File(appCtx.filesDir, LOG_FILE_NAME)
        if (!file.exists()) return
        runCatching {
            val lines = file.readLines(Charsets.UTF_8).takeLast(MAX_ENTRIES)
            val parsed = lines.mapNotNull { line -> parsePersistedLine(line) }
            localEvents.addAll(parsed)
            _entries.value = localEvents.toList()
        }
    }

    private fun parsePersistedLine(line: String): LogEntry? {
        // Format: MM-dd HH:mm:ss.SSS L/tag [source]: message
        if (line.length < 22) return null
        val ts = line.substring(0, 18)
        val rest = line.substring(19)
        val slashIdx = rest.indexOf('/')
        val colonIdx = rest.indexOf("]: ")
        if (slashIdx <= 0 || colonIdx <= slashIdx) return null
        val levelChar = rest[0]
        val tagAndSource = rest.substring(slashIdx + 1, colonIdx)
        val bracketIdx = tagAndSource.lastIndexOf(" [")
        val tag = if (bracketIdx > 0) tagAndSource.substring(0, bracketIdx) else tagAndSource
        val source = if (bracketIdx > 0) tagAndSource.substring(bracketIdx + 2) else "App"
        val message = rest.substring(colonIdx + 3)
        return LogEntry(
            id = idGen.getAndIncrement(),
            timestamp = ts,
            level = LogLevel.fromChar(levelChar),
            tag = tag,
            message = message,
            source = source,
        )
    }

    private fun parseLogcatLines(lines: List<String>, source: String): List<LogEntry> {
        return lines.mapNotNull { raw ->
            val m = LOGCAT_LINE_REGEX.matchEntire(raw.trim()) ?: return@mapNotNull null
            LogEntry(
                id = idGen.getAndIncrement(),
                timestamp = m.groupValues[1],
                level = LogLevel.fromChar(m.groupValues[2].first()),
                tag = m.groupValues[3],
                message = m.groupValues[4],
                source = source,
            )
        }
    }

    private fun runShellLogcat(cmd: Array<String>): List<String>? {
        return runCatching {
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(false)
                .start()
            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }.getOrNull()
    }
}
