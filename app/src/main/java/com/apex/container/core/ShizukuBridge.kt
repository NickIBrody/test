package com.apex.container.core

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStreamReader

data class CommandResult(
    val exitCode: Int,
    val output: String,
    val executedViaShizuku: Boolean
)

class ShizukuBridge(private val rootfsManager: RootfsManager) {

    companion object {
        private const val TAG = "ApexShizukuBridge"
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 2000
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received (UID=${getRemoteUid()})")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
    }

    fun registerListeners() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    fun unregisterListeners() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    fun isBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        if (!isBinderAlive()) return false
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermissionIfNeeded() {
        if (!isBinderAlive()) return
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
                !Shizuku.shouldShowRequestPermissionRationale()
            ) {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    fun getRemoteUid(): Int {
        return try {
            if (isBinderAlive()) Shizuku.getUid() else -1
        } catch (_: Exception) {
            -1
        }
    }

    fun getStatusDescription(): String {
        if (!isBinderAlive()) {
            return "Disconnected (Start Shizuku via Wireless Debugging)"
        }
        return if (isPermissionGranted()) {
            val uid = getRemoteUid()
            val role = if (uid == 0) "ROOT (UID 0)" else "ADB SHELL (UID $uid)"
            "Active — $role"
        } else {
            "Binder Alive — Permission Required"
        }
    }

    /**
     * Executes a command via Shizuku's privileged UID 2000 / UID 0 daemon if available,
     * or falls back to the local Virtual Root container environment.
     */
    suspend fun executePrivilegedCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        if (isBinderAlive() && isPermissionGranted()) {
            try {
                return@withContext executeViaShizukuRemoteProcess(command)
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku execution failed, falling back to local container shell", e)
            }
        }
        executeInVirtualRootContainer(command)
    }

    private fun executeViaShizukuRemoteProcess(command: String): CommandResult {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true

        val cmdArray = arrayOf("/system/bin/sh", "-c", command)
        val envList = rootfsManager.buildContainerEnvironment()
            .filterKeys { it != "LD_PRELOAD" } // Avoid preloading app-dir .so into UID 2000 process
            .map { "${it.key}=${it.value}" }
            .toTypedArray()

        val remoteProcess = method.invoke(null, cmdArray, envList, "/") as ShizukuRemoteProcess
        val outputBuilder = StringBuilder()

        BufferedReader(InputStreamReader(remoteProcess.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputBuilder.append(line).append("\n")
            }
        }
        BufferedReader(InputStreamReader(remoteProcess.errorStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputBuilder.append(line).append("\n")
            }
        }

        val exitCode = remoteProcess.waitFor()
        remoteProcess.destroy()

        return CommandResult(
            exitCode = exitCode,
            output = outputBuilder.toString().trimEnd(),
            executedViaShizuku = true
        )
    }

    /**
     * Executes a command locally inside ApexContainer with LD_PRELOAD=libvroot.so and APEX_ROOTFS.
     */
    suspend fun executeInVirtualRootContainer(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.directory(rootfsManager.rootfsDir)
            pb.redirectErrorStream(true)

            val env = pb.environment()
            env.putAll(rootfsManager.buildContainerEnvironment(env))

            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            CommandResult(
                exitCode = exitCode,
                output = output.trimEnd(),
                executedViaShizuku = false
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = 1,
                output = "Execution error: ${e.message}",
                executedViaShizuku = false
            )
        }
    }
}
