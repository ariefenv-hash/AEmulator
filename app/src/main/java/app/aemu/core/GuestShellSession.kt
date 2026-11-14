package app.aemu.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * A persistent guest shell for the terminal panel: one /system/bin/sh under the runner stays
 * alive between commands, so `cd`/`export` keep their effect across commands and every next
 * command skips the engine's cold start. Driving [GuestRunner.run] once per keystroke made
 * the panel feel dead — seconds of emulator start before any output, and a shell that forgot
 * everything between lines.
 *
 * Protocol: the command line is written, then an `echo` of the exit code with a marker; the
 * reader thread collects stdout+stderr (merged, like the adb legacy shell) until the marker
 * line arrives. There is no pty and no echo — the panel prints the command itself. A shell
 * that died (or was `exit`ed) is respawned with the same prefix on the next call, so the
 * panel keeps working no matter what was typed into it.
 */
class GuestShellSession(private val runner: GuestRunner, private val binDir: File, private val prefix: String) {

    private val lock = ReentrantLock()
    private var proc: Process? = null

    companion object {
        private const val MARKER = "__AEMU_DONE:"
        private const val MARKER_LINE = "echo \"$MARKER\$?\"\n"
    }

    fun run(cmd: String, timeoutMs: Long = 120_000): String = lock.withLock {
        repeat(2) { attempt ->
            val p = proc?.takeIf { it.isAlive } ?: start()?.also { proc = it } ?: return@withLock "shell: cannot start"
            val out = StringBuilder()
            val markerSeen = AtomicBoolean(false)
            val streamEnded = AtomicBoolean(false)
            val reader = thread(isDaemon = true) {
                runCatching {
                    val r = java.io.BufferedReader(p.inputStream.bufferedReader())
                    var line = r.readLine()
                    while (line != null) {
                        if (line.startsWith(MARKER)) markerSeen.set(true)
                        else synchronized(out) { out.append(line).append('\n') }
                        if (markerSeen.get()) break
                        line = r.readLine()
                    }
                }
                streamEnded.set(true)
            }
            try {
                val wrote = runCatching {
                    p.outputStream.write((cmd + "\n").toByteArray())
                    p.outputStream.write(MARKER_LINE.toByteArray())
                    p.outputStream.flush()
                }.isSuccess
                if (!wrote) {
                    // the shell is gone (the previous command killed it): respawn once
                    proc = null
                    runCatching { p.destroyForcibly() }
                    reader.join(1000)
                    return@repeat
                }
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (!markerSeen.get() && !streamEnded.get() && System.nanoTime() < deadline) Thread.sleep(40)
                return@withLock if (markerSeen.get()) {
                    synchronized(out) { out.toString().trimEnd('\n') }
                } else {
                    // timed out, or the shell exited (`exit`, fatal): reset for the next call
                    proc = null
                    runCatching { p.destroyForcibly() }
                    val notice = if (streamEnded.get()) "[shell exited; a new session starts on the next command]"
                        else "[timed out after ${timeoutMs / 1000}s; session reset]"
                    (if (synchronized(out) { out.isNotBlank() }) synchronized(out) { out.toString().trimEnd('\n') } + "\n" else "") + notice
                }
            } finally {
                reader.join(1000)
            }
        }
        "shell: cannot start"
    }

    /** Kill the session shell: called when the VM stops, so nothing leaks behind the engine. */
    fun close() {
        lock.withLock {
            proc?.let { runCatching { it.destroyForcibly() } }
            proc = null
        }
    }

    private fun start(): Process? = runCatching {
        val pb = ProcessBuilder(runner.cmdline(listOf("/system/bin/sh", "-i")))
            .directory(binDir)
            .redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(runner.env(mapOf("HOME" to "/data/local/tmp", "TERM" to "dumb")))
        val p = pb.start()
        // the PATH fix and the am/pm/etc tool wrappers ride at every start: idempotent for a
        // fresh shell, and a respawned one needs them just as much as the first
        p.outputStream.write((prefix + "\n").toByteArray())
        p.outputStream.flush()
        p
    }.getOrNull()
}
