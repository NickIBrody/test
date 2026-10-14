package com.apex.container.core

import android.content.Context
import android.os.Environment
import android.system.Os
import android.util.Log
import java.io.File

class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "ApexRootfsManager"
    }

    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    val nativeLibDir: String
        get() = context.applicationInfo.nativeLibraryDir

    val vrootSoPath: String
        get() = File(nativeLibDir, "libvroot.so").absolutePath

    val suBinarySoPath: String
        get() = File(nativeLibDir, "libsu_bin.so").absolutePath

    /**
     * Initializes the container directory layout, configuration files (/etc/passwd, /etc/group,
     * /etc/hosts, /etc/profile), and symlinks (/bin/su, /system/bin/su, /sdcard).
     */
    fun initializeRootfs(): Boolean {
        return try {
            val directories = listOf(
                "etc",
                "root",
                "bin",
                "usr/bin",
                "usr/lib",
                "tmp",
                "system/bin",
                "system/xbin",
                "system/etc",
                "data/local/tmp",
                "var/run"
            )

            directories.forEach { relPath ->
                val dir = File(rootfsDir, relPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
            }

            writeEtcConfigFiles()
            setupSymlinks()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize virtual rootfs", e)
            false
        }
    }

    private fun writeEtcConfigFiles() {
        val passwdFile = File(rootfsDir, "etc/passwd")
        if (!passwdFile.exists()) {
            passwdFile.writeText(
                """
                root:x:0:0:root:/root:/system/bin/sh
                shell:x:2000:2000:shell:/data/local/tmp:/system/bin/sh
                nobody:x:9999:9999:nobody:/:/system/bin/false
                """.trimIndent() + "\n"
            )
        }

        val groupFile = File(rootfsDir, "etc/group")
        if (!groupFile.exists()) {
            groupFile.writeText(
                """
                root:x:0:root
                shell:x:2000:shell
                sdcard_rw:x:1015:root,shell
                inet:x:3003:root,shell
                """.trimIndent() + "\n"
            )
        }

        val hostsFile = File(rootfsDir, "etc/hosts")
        if (!hostsFile.exists()) {
            hostsFile.writeText(
                """
                127.0.0.1       localhost
                ::1             ip6-localhost
                127.0.1.1       apex-container.local apex
                """.trimIndent() + "\n"
            )
        }

        val profileFile = File(rootfsDir, "etc/profile")
        if (!profileFile.exists()) {
            profileFile.writeText(
                """
                export USER=root
                export LOGNAME=root
                export HOME=/root
                export PS1='root@apex:\\w # '
                alias ll='ls -la'
                alias id='id'
                """.trimIndent() + "\n"
            )
        }
    }

    private fun setupSymlinks() {
        val suTarget = suBinarySoPath
        val symlinkLocations = listOf(
            File(rootfsDir, "bin/su"),
            File(rootfsDir, "usr/bin/su"),
            File(rootfsDir, "system/bin/su"),
            File(rootfsDir, "system/xbin/su")
        )

        symlinkLocations.forEach { linkFile ->
            createOrReplaceSymlink(suTarget, linkFile)
        }

        // Transparent host storage mount symlink inside rootfs/sdcard
        val hostSdcard = Environment.getExternalStorageDirectory().absolutePath
        val rootfsSdcard = File(rootfsDir, "sdcard")
        createOrReplaceSymlink(hostSdcard, rootfsSdcard)
    }

    private fun createOrReplaceSymlink(targetPath: String, linkFile: File) {
        try {
            if (linkFile.exists() || isSymlink(linkFile)) {
                linkFile.delete()
            }
            Os.symlink(targetPath, linkFile.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Os.symlink failed for ${linkFile.absolutePath} -> $targetPath: ${e.message}")
        }
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            val canon = file.canonicalFile
            canon.absolutePath != file.absolutePath
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the environment map used to spawn processes inside the virtual root container.
     */
    fun buildContainerEnvironment(baseEnv: Map<String, String> = System.getenv()): Map<String, String> {
        val env = HashMap(baseEnv)
        val rootPath = rootfsDir.absolutePath
        val currentPath = env["PATH"] ?: "/system/bin:/system/xbin:/product/bin"

        env["LD_PRELOAD"] = vrootSoPath
        env["APEX_VROOT_SO"] = vrootSoPath
        env["APEX_NATIVE_DIR"] = nativeLibDir
        env["APEX_ROOTFS"] = rootPath
        env["HOME"] = "/root"
        env["USER"] = "root"
        env["LOGNAME"] = "root"
        env["SHELL"] = "/system/bin/sh"
        env["PS1"] = "root@apex:\\w # "
        env["TMPDIR"] = File(rootfsDir, "tmp").absolutePath
        env["PATH"] = "$rootPath/bin:$rootPath/usr/bin:$rootPath/system/bin:$rootPath/system/xbin:$currentPath"
        return env
    }

    fun isSdcardAccessible(): Boolean {
        val sdcard = Environment.getExternalStorageDirectory()
        return sdcard.exists() && sdcard.canRead()
    }
}
