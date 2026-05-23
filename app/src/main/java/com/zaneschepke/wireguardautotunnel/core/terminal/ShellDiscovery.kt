package com.zaneschepke.wireguardautotunnel.core.terminal

import android.content.Context
import java.io.File

object ShellDiscovery {

    data class Shell(
        val id: String,
        val name: String,
        val command: String,
        val args: Array<String>,
        val env: Array<String>,
        val cwd: String,
        val available: Boolean = true,
        val needsSetup: Boolean = false,
    )

    fun getShells(ctx: Context): List<Shell> {
        val shells = mutableListOf<Shell>()
        val filesDir = ctx.filesDir.absolutePath
        val nativeDir = ctx.applicationInfo.nativeLibraryDir

        File(filesDir, "home").mkdirs()
        File(filesDir, "tmp").mkdirs()

        shells.add(Shell(
            id = "sh", name = "Android Shell", command = "/system/bin/sh", args = arrayOf(),
            env = arrayOf("TERM=xterm-256color", "HOME=$filesDir/home", "TMPDIR=$filesDir/tmp", "PATH=/system/bin:/system/xbin"),
            cwd = "$filesDir/home",
        ))

        val alpineInstalled = ProotBootstrap.isInstalled(ctx)
        val prootBin = ProotBootstrap.findProotXed(ctx)
        if (prootBin != null && alpineInstalled) {
            val rootfs = ProotBootstrap.rootfsDir(ctx).absolutePath
            File(rootfs, "root").mkdirs()
            shells.add(Shell(
                id = "alpine", name = "Alpine (proot)", command = prootBin.absolutePath,
                args = buildProotArgs(rootfs, filesDir), env = buildProotEnv(filesDir, nativeDir), cwd = filesDir,
            ))
        } else {
            shells.add(Shell(id = "alpine", name = "Alpine (setup needed)", command = "", args = arrayOf(), env = arrayOf(), cwd = "", available = false, needsSetup = true))
        }
        return shells
    }

    private fun buildProotArgs(rootfs: String, filesDir: String): Array<String> {
        val args = mutableListOf("--kill-on-exit")
        fun bind(src: String, dst: String? = null) { args.addAll(listOf("-b", if (dst != null) "$src:$dst" else src)) }

        for (mnt in listOf("/apex", "/system", "/vendor", "/product", "/system_ext", "/linkerconfig/ld.config.txt")) {
            if (File(mnt).exists()) bind(File(mnt).canonicalPath)
        }
        bind("/dev"); bind("/dev/urandom", "/dev/random"); bind("/proc"); bind("/sys")
        bind("/proc/self/fd", "/dev/fd"); bind(filesDir)
        bind(File(filesDir, "home").also { it.mkdirs() }.absolutePath, "/root")
        bind("/proc/self/fd/0", "/dev/stdin"); bind("/proc/self/fd/1", "/dev/stdout"); bind("/proc/self/fd/2", "/dev/stderr")

        val shell = if (File(rootfs, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
        args.addAll(listOf(
            "-r", rootfs, "-0", "--link2symlink", "--sysvipc", "-L", "-w", "/root",
            "/usr/bin/env", "PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME=/root", "USER=root", "TERM=xterm-256color", "TMPDIR=/tmp",
            shell, "--login",
        ))
        return args.toTypedArray()
    }

    private fun buildProotEnv(filesDir: String, nativeDir: String): Array<String> {
        val env = mutableListOf("PROOT_TMP_DIR=$filesDir/tmp", "PROOT_VERBOSE=-1", "LD_LIBRARY_PATH=$filesDir")
        val loader = File(nativeDir, "libproot.so")
        val loader32 = File(nativeDir, "libproot32.so")
        if (loader.exists()) env.add("PROOT_LOADER=${loader.absolutePath}")
        if (loader32.exists()) env.add("PROOT_LOADER32=${loader32.absolutePath}")
        return env.toTypedArray()
    }
}
