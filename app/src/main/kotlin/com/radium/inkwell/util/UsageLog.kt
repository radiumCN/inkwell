package com.radium.inkwell.util

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Local diagnostics, independent of developer logcat recording. No network and no UI-thread file writes.
 * Callers supply fixed event/field names, numbers, enums and session-local references, never input/body text.
 */
internal object UsageLog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val session = UUID.randomUUID().toString().take(8)
    private val referenceSalt = UUID.randomUUID().toString()
    private val started = System.nanoTime()
    private val sequence = AtomicLong()
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val privateField = Regex("(?i)(?:^|_)(?:password|passwd|token|secret|authorization|cookie|query|text|content|title|url|uri|path|email|api[_-]?key)(?:_|$)")
    private val commands = Channel<Command>(512)
    @Volatile private var initialized = false
    @Volatile private var screen = "starting"

    private sealed interface Command {
        data class Append(val line: String) : Command
        data class Snapshot(val result: CompletableDeferred<String>) : Command
        data class Clear(val result: CompletableDeferred<Unit>) : Command
    }

    fun initialize(directory: File, version: String) = synchronized(lock) {
        if (initialized) return@synchronized
        val writer = UsageLogStore(directory)
        initialized = true
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Append -> runCatching { writer.append(command.line) }.onFailure { failures.incrementAndGet() }
                    is Command.Snapshot -> runCatching { writer.snapshot() }
                        .onSuccess { command.result.complete(it + encode("log.snapshot", arrayOf("dropped" to dropped.get(), "write_failures" to failures.get(), "window" to "recent_bounded_history")) + "\n") }
                        .onFailure { command.result.completeExceptionally(it) }
                    is Command.Clear -> runCatching { writer.clear() }
                        .onSuccess { command.result.complete(Unit) }
                        .onFailure { command.result.completeExceptionally(it) }
                }
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // The async queue may not drain before Android kills the process. Persist one final safe event.
            runCatching {
                val frames = error.stackTrace.take(8).mapIndexed { index, frame -> "frame_$index" to frame.toString() }
                writer.append(encode("app.crash", arrayOf("thread" to thread.name, "error" to error.javaClass.simpleName, *frames.toTypedArray())))
            }
            previous?.uncaughtException(thread, error)
        }
        event("app.start", "version" to version)
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) = synchronized(lock) {
        if (!initialized) return@synchronized
        if (!commands.trySend(Command.Append(encode(name, fields))).isSuccess) dropped.incrementAndGet()
    }

    /** Never persist raw book/source IDs; correlates events only within this app process. */
    fun reference(id: String): String = MessageDigest.getInstance("SHA-256")
        .digest((referenceSalt + id).toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    fun screenVisible(name: String, depth: Int) {
        screen = name
        event("navigation.visible", "depth" to depth)
    }

    suspend fun snapshot(): String = withTimeout(5_000) {
        check(initialized) { "操作日志尚未初始化" }
        val result = CompletableDeferred<String>()
        commands.send(Command.Snapshot(result))
        result.await()
    }

    suspend fun clear() = withTimeout(5_000) {
        if (!initialized) return@withTimeout
        val result = CompletableDeferred<Unit>()
        commands.send(Command.Clear(result))
        result.await()
        event("log.cleared")
    }

    private fun encode(name: String, fields: Array<out Pair<String, Any?>>): String = buildJsonObject {
        put("time", Instant.now().toString())
        put("session", session)
        put("seq", sequence.incrementAndGet())
        put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
        put("event", name.take(64))
        put("screen", screen)
        put("fields", buildJsonObject {
            for ((key, value) in fields.take(12)) {
                val safe = if (privateField.containsMatchIn(key)) "[未记录]" else {
                    FeedbackLogSanitizer.sanitize(value.toString()).replace('\n', ' ').replace('\r', ' ').take(96)
                }
                put(key.take(32), safe)
            }
        })
    }.toString()
}
