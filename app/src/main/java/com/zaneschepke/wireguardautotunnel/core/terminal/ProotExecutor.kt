package com.zaneschepke.wireguardautotunnel.core.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

object ProotExecutor {

    private const val TAG = "ProotExecutor"
    @Volatile private var currentProcess: Process? = null

    fun exec(context: Context, command: String, timeoutMs: Long = 120_000): String {
        val filesDir = context.filesDir.absolutePath
        val rootfs = File(filesDir, "env/alpine")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val prootBin = ProotBootstrap.findProotXed(context) ?: return "Error: proot not found"
        if (!rootfs.isDirectory) return "Error: rootfs not installed"

        val talloc = File(filesDir, "libtalloc.so.2")
        if (!talloc.exists()) {
            val src = File(nativeLibDir, "libtalloc.so")
            if (src.exists()) src.inputStream().use { i -> talloc.outputStream().use { o -> i.copyTo(o) } }
        }
        File(filesDir, "tmp").mkdirs()

        val args = buildArgs(rootfs, filesDir, command)
        val env = buildEnv(filesDir, nativeLibDir)

        return try {
            val pb = ProcessBuilder(listOf(prootBin.absolutePath) + args)
            pb.environment().clear(); pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val process = pb.start()
            currentProcess = process
            val output = ShellExecutor.readAll(process.inputStream, timeoutMs)
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            currentProcess = null
            output.lines().filter { !it.startsWith("proot warning:") && !it.startsWith("proot info:") }
                .joinToString("\n").trim().ifEmpty { "(exit ${process.exitValue()})" }
        } catch (e: Exception) { currentProcess = null; Log.e(TAG, "exec failed", e); "Error: ${e.message}" }
    }

    fun cancel() { currentProcess?.destroyForcibly(); currentProcess = null }

    private fun buildArgs(rootfs: File, filesDir: String, command: String): List<String> {
        val args = mutableListOf("--kill-on-exit")
        fun bind(src: String, dst: String? = null) { args.addAll(listOf("-b", if (dst != null) "$src:$dst" else src)) }

        for (mnt in listOf("/apex", "/system", "/vendor", "/product", "/system_ext", "/linkerconfig/ld.config.txt")) {
            if (File(mnt).exists()) bind(File(mnt).canonicalPath)
        }
        bind("/dev"); bind("/dev/urandom", "/dev/random"); bind("/proc"); bind("/sys")
        bind("/proc/self/fd", "/dev/fd"); bind(filesDir)
        val homeDir = File(filesDir, "home").also { it.mkdirs() }
        bind(homeDir.absolutePath, "/root")
        bind("/proc/self/fd/0", "/dev/stdin"); bind("/proc/self/fd/1", "/dev/stdout"); bind("/proc/self/fd/2", "/dev/stderr")

        val shell = if (File(rootfs, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
        args.addAll(listOf(
            "-r", rootfs.absolutePath, "-0", "--link2symlink", "--sysvipc", "-L", "-w", "/root",
            "/usr/bin/env", "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME=/root", "USER=root", "TERM=xterm-256color", "TMPDIR=/tmp",
            shell, "-c", command,
        ))
        return args
    }

    private fun buildEnv(filesDir: String, nativeDir: String): Map<String, String> {
        val env = mutableMapOf("PROOT_TMP_DIR" to "$filesDir/tmp", "PROOT_VERBOSE" to "-1", "LD_LIBRARY_PATH" to filesDir)
        val loader = File(nativeDir, "libproot.so")
        val loader32 = File(nativeDir, "libproot32.so")
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        if (loader32.exists()) env["PROOT_LOADER32"] = loader32.absolutePath
        return env
    }
}
