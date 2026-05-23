package com.zaneschepke.wireguardautotunnel.core.terminal

import java.io.InputStream

object ShellExecutor {

    fun exec(command: String, timeoutMs: Long = 60_000): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val output = readAll(process.inputStream, timeoutMs)
        val stderr = readAll(process.errorStream, 1_000)
        process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        (output + stderr).trim().ifEmpty { "(exit ${process.exitValue()})" }
    } catch (e: Exception) { "Error: ${e.message}" }

    internal fun readAll(stream: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder()
        val thread = Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(4096)
                    var n: Int
                    while (reader.read(buf).also { n = it } != -1) {
                        sb.append(buf, 0, n)
                        if (sb.length > 8000) break
                    }
                }
            } catch (_: Exception) {}
        }
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) { thread.interrupt(); sb.append("\n[timeout]") }
        return sb.toString()
    }
}
