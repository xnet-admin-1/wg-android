package com.zaneschepke.wireguardautotunnel.core.terminal

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object ProotBootstrap {

    private const val TAG = "ProotBootstrap"
    private const val ROOTFS_VERSION = "rootfs_alpine_v1"

    fun envDir(ctx: Context) = File(ctx.filesDir, "env")
    fun rootfsDir(ctx: Context) = File(envDir(ctx), "alpine")
    private fun marker(ctx: Context, name: String) = File(envDir(ctx), ".$name")

    fun isInstalled(ctx: Context): Boolean {
        val rootfs = rootfsDir(ctx)
        return marker(ctx, ROOTFS_VERSION).exists() &&
            rootfs.isDirectory &&
            (File(rootfs, "bin/busybox").exists() || File(rootfs, "bin/sh").exists())
    }

    fun findProotXed(ctx: Context): File? {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        for (name in arrayOf("libproot-xed.so", "libroot-xed.so")) {
            val f = File(nativeDir, name)
            if (f.isFile) return f
        }
        return null
    }

    fun setup(ctx: Context, logCb: (String) -> Unit): Boolean {
        val l: (String) -> Unit = { msg -> Log.d(TAG, msg); logCb(msg) }
        try {
            val filesDir = ctx.filesDir
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val envDir = envDir(ctx)
            val rootfs = rootfsDir(ctx)

            l("Creating directories...")
            listOf(envDir, rootfs, File(filesDir, "tmp"), File(filesDir, "home")).forEach { it.mkdirs() }

            val tallocSrc = File(nativeDir, "libtalloc.so")
            val tallocDst = File(filesDir, "libtalloc.so.2")
            if (tallocSrc.exists()) {
                tallocSrc.inputStream().use { i -> tallocDst.outputStream().use { o -> i.copyTo(o) } }
                l("libtalloc.so.2 ready")
            } else { l("ERROR: libtalloc.so not found"); return false }

            val bsdtar = File(nativeDir, "libbsdtar.so")
            if (!bsdtar.exists()) { l("ERROR: libbsdtar.so not found"); return false }

            val versionMarker = marker(ctx, ROOTFS_VERSION)
            if (!versionMarker.exists() || !File(rootfs, "bin/busybox").exists()) {
                if (rootfs.isDirectory) { rootfs.deleteRecursively(); rootfs.mkdirs() }

                val arch = System.getProperty("os.arch")?.lowercase() ?: "aarch64"
                val pdArch = if ("aarch64" in arch || "arm64" in arch) "aarch64" else "x86_64"
                val url = "https://github.com/xnet-admin-1/box/releases/download/rootfs-alpine-3.21.3/box-alpine-3.21-$pdArch.tar.xz"
                val tarball = File(envDir, "rootfs.tar.xz")

                l("Downloading Alpine 3.21 rootfs ($pdArch)...")
                download(url, tarball, l)

                l("Extracting rootfs...")
                val ok = runBsdtar(bsdtar, tarball, rootfs, l)
                tarball.delete()
                if (!ok || !File(rootfs, "bin/busybox").exists()) { l("ERROR: Extraction failed"); return false }
                versionMarker.writeText("alpine-3.21")
                l("Rootfs extracted")
            } else { l("Rootfs already installed") }

            patchRootfs(rootfs, l)
            distroSetup(ctx, l)
            l("Alpine environment ready!")
            return true
        } catch (e: Exception) { l("ERROR: ${e.message}"); Log.e(TAG, "setup failed", e); return false }
    }

    fun rebuild(ctx: Context, logCb: (String) -> Unit): Boolean {
        marker(ctx, ROOTFS_VERSION).delete()
        rootfsDir(ctx).deleteRecursively()
        return setup(ctx, logCb)
    }

    private fun runBsdtar(bsdtar: File, tarball: File, destDir: File, log: (String) -> Unit): Boolean {
        destDir.mkdirs()
        return try {
            val pb = ProcessBuilder(listOf(bsdtar.absolutePath, "-xf", tarball.absolutePath, "-C", destDir.absolutePath, "--no-same-owner"))
            pb.redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = bsdtar.parentFile?.absolutePath ?: ""
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val ok = proc.waitFor(120, TimeUnit.SECONDS)
            val exit = if (ok) proc.exitValue() else -1
            if (exit != 0) { log("bsdtar error (exit $exit): ${output.take(500)}"); false } else true
        } catch (e: Exception) { log("bsdtar failed: ${e.message}"); false }
    }

    private fun distroSetup(ctx: Context, log: (String) -> Unit) {
        val setupMarker = File(envDir(ctx), ".distro_setup_done")
        if (setupMarker.exists() && verifyAdbInstalled(ctx)) return
        log("Running apk update && installing tools...")
        try {
            val output = ProotExecutor.exec(ctx, "apk update && apk add android-tools", timeoutMs = 120_000)
            if (verifyAdbInstalled(ctx)) {
                setupMarker.writeText("done")
                log("Packages installed")
            } else {
                setupMarker.delete()
                log("Package install failed: $output")
            }
        } catch (e: Exception) {
            setupMarker.delete()
            Log.w(TAG, "distro setup failed: ${e.message}")
            log("Package setup failed (${e.message})")
        }
    }

    private fun verifyAdbInstalled(ctx: Context): Boolean {
        val result = ProotExecutor.exec(ctx, "which adb", timeoutMs = 10_000)
        return result.contains("/adb")
    }

    private fun download(urlStr: String, dest: File, log: (String) -> Unit) {
        var url = URL(urlStr)
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location") ?: break
                url = URL(url, loc); conn.disconnect()
                if (++redirects > 5) { log("ERROR: too many redirects"); return }
                continue
            }
            if (code != 200) { log("ERROR: HTTP $code"); conn.disconnect(); return }
            break
        }
        val total = conn.contentLength.toLong()
        var downloaded = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n); downloaded += n
                    if (total > 0 && downloaded % (512 * 1024) < 65536) log("  ${downloaded / 1024}KB / ${total / 1024}KB")
                }
            }
        }
        conn.disconnect()
        log("  Download complete (${dest.length() / 1024}KB)")
    }

    private fun patchRootfs(rootfs: File, log: (String) -> Unit) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        for (d in listOf("tmp", "var/tmp", "home", "root")) File(rootfs, d).mkdirs()
        File(rootfs, "tmp").setWritable(true, false)
        listOf("bin", "sbin", "usr/bin", "usr/sbin").forEach { dir ->
            File(rootfs, dir).walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true) }
        }
        listOf("lib", "usr/lib").forEach { dir ->
            File(rootfs, dir).walkTopDown().filter { it.isFile && it.name.contains(".so") }.forEach { it.setExecutable(true) }
        }
        listOf("lib/ld-musl-aarch64.so.1", "bin/sh", "bin/busybox").forEach {
            val f = File(rootfs, it); if (f.exists()) f.setExecutable(true)
        }
        File(rootfs, "root/.profile").writeText("export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin\nexport HOME=/root\nexport TERM=xterm-256color\nexport TMPDIR=/tmp\nPS1='box:\\w# '\n")
        val reposFile = File(rootfs, "etc/apk/repositories")
        File(rootfs, "etc/apk").mkdirs()
        if (!reposFile.exists() || reposFile.readText().isBlank()) {
            reposFile.writeText("https://dl-cdn.alpinelinux.org/alpine/v3.21/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.21/community\n")
        }
        log("Rootfs patched")
    }
}
