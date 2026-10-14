package com.apex.container.core

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class SuSocketServer(
    private val shizukuBridge: ShizukuBridge
) {

    companion object {
        private const val TAG = "ApexSuSocketServer"
        const val SOCKET_NAME = "apex_privileged_socket"
        private const val EXIT_MARKER = "__APEX_EXIT_CODE__:"
    }

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: LocalServerSocket? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        serverJob = serverScope.launch {
            try {
                serverSocket = LocalServerSocket(SOCKET_NAME)
                isRunning = true
                Log.i(TAG, "LocalServerSocket('$SOCKET_NAME') listening in abstract namespace")

                while (isActive && isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "SuSocketServer error", e)
                }
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun handleClient(client: LocalSocket) {
        client.use { socket ->
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val writer = OutputStreamWriter(socket.outputStream)

                val command = reader.readLine()?.trim().orEmpty()
                if (command.isEmpty()) {
                    writer.write("${EXIT_MARKER}0\n")
                    writer.flush()
                    return
                }

                Log.d(TAG, "Received command from native su client: $command")
                val result = shizukuBridge.executePrivilegedCommand(command)

                if (result.output.isNotEmpty()) {
                    writer.write(result.output)
                    if (!result.output.endsWith("\n")) {
                        writer.write("\n")
                    }
                }
                writer.write("$EXIT_MARKER${result.exitCode}\n")
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling su_client request", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        serverJob?.cancel()
    }
}
