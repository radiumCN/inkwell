package com.radium.inkwell.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 开发者模式下的运行日志记录：起一个只盯本进程的 `logcat` 子进程，直接写进 `filesDir/logs/`。
 *
 * 为什么是 logcat 而不是自己包一层 Logger：要的是「完整运行日志」—— 系统替我们打的
 * （Activity 生命周期、OkHttp/Coil 的报错、StrictMode、AndroidRuntime 的崩溃堆栈）
 * 一样有价值，自己的 Logger 只收得到自己写的那几行。`--pid` 从 Android 7 起就有（minSdk 33），
 * 读本进程的日志也不需要任何权限。
 *
 * 「从启动起」靠的是 logcat 的默认行为：不带 `-d` 时先把缓冲区里已有的内容吐出来再跟着往下流。
 * 所以 Application.onCreate 里异步读完开关再起进程（几十毫秒），启动那段并不会丢 ——
 * 中途打开开关也一样，只要系统环形缓冲区还没把它们挤掉。
 *
 * 一次录制一个文件（`run-时间戳.log`，logcat 自己按 [ROTATE_KB] 轮转出 `.1`、`.2`…），
 * 只留最近 [MAX_SESSIONS] 次：上一次闪退的现场正在「上一个文件」里，不能每次启动就覆盖。
 */
class RunLogRecorder(private val context: Context) {

    private val logDir = File(context.filesDir, "logs")
    private val lock = Any()
    private var process: Process? = null
    /** 当前这次录制的文件名；清除日志时要跳过它，删了 logcat 还在往已删的 inode 里写 */
    private var currentBase: String? = null
    private var crashHandlerInstalled = false

    val isRecording: Boolean get() = synchronized(lock) { process?.isAlive == true }

    fun start(): Unit = synchronized(lock) {
        if (process?.isAlive == true) return@synchronized
        logDir.mkdirs()
        pruneSessions(keep = MAX_SESSIONS - 1)
        val base = "run-${stamp()}.log"
        process = runCatching {
            ProcessBuilder(
                "logcat",
                // crash 缓冲区单独存放 FATAL EXCEPTION，只读 main 会把最要紧的那段漏掉
                "-b", "main,system,crash",
                "-v", "threadtime",
                "--pid=${android.os.Process.myPid()}",
                "-f", File(logDir, base).path,
                "-r", ROTATE_KB.toString(),
                "-n", ROTATE_COUNT.toString(),
            )
                .redirectErrorStream(true)
                // -f 模式下 stdout 基本没东西，但管道不读满了会把 logcat 卡死，丢进黑洞最省心
                .redirectOutput(File("/dev/null"))
                .start()
        }.getOrNull()
        currentBase = base.takeIf { process != null }
        installCrashHandler()
    }

    fun stop(): Unit = synchronized(lock) {
        process?.destroy()
        process = null
        currentBase = null
    }

    /** 已保存的日志：次数与总字节数，给设置页的副标题用 */
    suspend fun summary(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val files = logFiles()
        files.map { sessionOf(it.name) }.distinct().size to files.sumOf { it.length() }
    }

    /** 删掉除当前录制以外的全部日志 */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        val current = synchronized(lock) { currentBase }
        logFiles().filter { sessionOf(it.name) != current }.forEach { it.delete() }
    }

    /**
     * 打包成 zip，落在 `cacheDir/export/`（FileProvider 只交出这个目录，见 file_paths.xml）。
     * 没在录制时现抓一份 `logcat -d` 快照 —— 开关没开也能导出点东西，总比一个空包强。
     */
    suspend fun export(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        // 上次导出的包分享完就没用了，别越攒越多
        dir.listFiles { f -> f.name.startsWith(EXPORT_PREFIX) }?.forEach { it.delete() }
        val out = File(dir, "$EXPORT_PREFIX${stamp()}.zip")
        val tmp = File.createTempFile("logs", ".tmp", dir)
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("device.txt"))
                zip.write(deviceInfo().toByteArray())
                zip.closeEntry()
                logFiles().forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                if (!isRecording) {
                    zip.putNextEntry(ZipEntry("logcat-snapshot.txt"))
                    val p = ProcessBuilder(
                        "logcat", "-d", "-b", "main,system,crash", "-v", "threadtime",
                        "--pid=${android.os.Process.myPid()}",
                    ).redirectErrorStream(true).start()
                    p.inputStream.use { it.copyTo(zip) }
                    p.waitFor()
                    zip.closeEntry()
                }
            }
            if (!tmp.renameTo(out)) error("无法写入导出文件")
        } finally {
            tmp.delete()
        }
        out
    }

    /**
     * 崩溃兜底：进程崩的那一刻，logcat 子进程跟着整组被杀，AndroidRuntime 那几行
     * 未必来得及落盘。录制中就把堆栈同步写进本次会话旁边的 `.crash` 文件，再交还给原处理器。
     */
    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val base = synchronized(lock) { currentBase }
                if (base != null) {
                    File(logDir, "$base.crash").writeText(
                        "${stamp()} 线程 ${thread.name} 崩溃\n${e.stackTraceToString()}",
                    )
                }
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun logFiles(): List<File> =
        logDir.listFiles { f -> f.isFile && f.name.startsWith("run-") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun pruneSessions(keep: Int) {
        val files = logFiles()
        val sessions = files.map { sessionOf(it.name) }.distinct().sorted()
        val drop = sessions.dropLast(keep).toSet()
        files.filter { sessionOf(it.name) in drop }.forEach { it.delete() }
    }

    /** `run-20261025-155400.log.1` / `.crash` → `run-20261025-155400.log` */
    private fun sessionOf(name: String): String = name.substringBefore(".log") + ".log"

    private fun deviceInfo(): String {
        val pkg = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return buildString {
            appendLine("应用版本: ${pkg?.versionName} (${pkg?.longVersionCode})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("导出时间: ${stamp()}")
            appendLine("正在录制: $isRecording")
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private companion object {
        const val MAX_SESSIONS = 5
        /** 单文件 2MB，轮转 3 份：一次录制封顶约 8MB，忘了关也不会把存储吃光 */
        const val ROTATE_KB = 2048
        const val ROTATE_COUNT = 3
        const val EXPORT_PREFIX = "inkwell-logs-"
    }
}
