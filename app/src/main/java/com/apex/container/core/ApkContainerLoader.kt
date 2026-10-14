package com.apex.container.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val sourceApkPath: String,
    val nativeLibraryDir: String,
    val isSystemApp: Boolean,
    val isImported: Boolean = false
)

data class ContainerLaunchResult(
    val success: Boolean,
    val packageName: String,
    val loadedClassesInfo: String,
    val details: String
)

/**
 * Custom ContextWrapper that redirects getFilesDir(), getCacheDir(), and getDataDir()
 * into the ApexContainer virtual rootfs sandbox for dynamically loaded guest APKs.
 */
class ContainerPluginContext(
    base: Context,
    private val guestPackageName: String,
    private val rootfsManager: RootfsManager
) : ContextWrapper(base) {

    private val guestDataRoot: File by lazy {
        File(rootfsManager.rootfsDir, "data/data/$guestPackageName").apply { mkdirs() }
    }

    override fun getPackageName(): String = guestPackageName

    override fun getDataDir(): File = guestDataRoot

    override fun getFilesDir(): File = File(guestDataRoot, "files").apply { mkdirs() }

    override fun getCacheDir(): File = File(guestDataRoot, "cache").apply { mkdirs() }
}

class ApkContainerLoader(
    private val context: Context,
    private val rootfsManager: RootfsManager
) {

    companion object {
        private const val TAG = "ApexApkLoader"
        private var vrootLoaded = false

        fun ensureNativeHookLoaded(): Boolean {
            if (vrootLoaded) return true
            return try {
                System.loadLibrary("vroot")
                vrootLoaded = true
                Log.i(TAG, "System.loadLibrary("vroot") succeeded inside container process")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load libvroot.so via JNI", t)
                false
            }
        }
    }

    private val appsSandboxDir: File
        get() = File(context.filesDir, "container_apps").apply { mkdirs() }

    private val optimizedDexDir: File
        get() = File(context.codeCacheDir, "apex_opt_dex").apply { mkdirs() }

    /**
     * Lists user-installed (and optionally system) packages available to import into ApexContainer.
     */
    suspend fun getInstalledApplications(includeSystem: Boolean = false): List<InstalledAppItem> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val importedPackages = appsSandboxDir.listFiles()?.map { it.name }?.toSet().orEmpty()

            apps.asSequence()
                .filter { appInfo ->
                    val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    (includeSystem || !isSys) && appInfo.packageName != context.packageName
                }
                .map { appInfo ->
                    val label = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        appInfo.packageName
                    }
                    InstalledAppItem(
                        packageName = appInfo.packageName,
                        label = label,
                        sourceApkPath = appInfo.sourceDir,
                        nativeLibraryDir = appInfo.nativeLibraryDir ?: "",
                        isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        isImported = importedPackages.contains(appInfo.packageName)
                    )
                }
                .sortedWith(compareByDescending<InstalledAppItem> { it.isImported }.thenBy { it.label.lowercase() })
                .toList()
        }

    /**
     * Imports a package's base APK into the container's local sandbox and prepares its virtual /data/data/<pkg> directory.
     */
    suspend fun importApkIntoContainer(app: InstalledAppItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetPkgDir = File(appsSandboxDir, app.packageName).apply { mkdirs() }
            val destApk = File(targetPkgDir, "base.apk")
            File(app.sourceApkPath).copyTo(destApk, overwrite = true)
            // Android 14+ requires dynamically loaded DEX/APK files to be read-only
            destApk.setReadOnly()

            File(rootfsManager.rootfsDir, "data/data/${app.packageName}/files").mkdirs()
            File(rootfsManager.rootfsDir, "data/data/${app.packageName}/cache").mkdirs()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import APK for ${app.packageName}", e)
            false
        }
    }

    /**
     * Dynamically loads an APK inside the current ApexContainer shared process address space
     * with libvroot.so pre-loaded and a sandboxed ContainerPluginContext.
     */
    suspend fun launchInContainerProcess(app: InstalledAppItem): ContainerLaunchResult =
        withContext(Dispatchers.IO) {
            try {
                val hookActive = ensureNativeHookLoaded()
                val importedApk = File(File(appsSandboxDir, app.packageName), "base.apk")
                val apkToLoad = if (importedApk.exists()) {
                    importedApk.setReadOnly()
                    importedApk.absolutePath
                } else {
                    app.sourceApkPath
                }

                val combinedNativeLibSearchPath = listOfNotNull(
                    app.nativeLibraryDir.takeIf { it.isNotBlank() },
                    rootfsManager.nativeLibDir
                ).joinToString(File.pathSeparator)

                val classLoader = DexClassLoader(
                    apkToLoad,
                    optimizedDexDir.absolutePath,
                    combinedNativeLibSearchPath,
                    context.classLoader
                )

                val pm = context.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkToLoad,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
                )

                val appClassName = pkgInfo?.applicationInfo?.className
                val entryActivityName = pkgInfo?.activities?.firstOrNull()?.name
                val pluginContext = ContainerPluginContext(context, app.packageName, rootfsManager)

                val loadedSummary = StringBuilder()
                loadedSummary.append("Native Hook (libvroot.so): ${if (hookActive) "ACTIVE (UID=0)" else "INACTIVE"}\n")
                loadedSummary.append("APK Source: $apkToLoad\n")
                loadedSummary.append("Isolated Data Dir: ${pluginContext.dataDir.absolutePath}\n")

                if (!appClassName.isNullOrBlank()) {
                    val appClass = classLoader.loadClass(appClassName)
                    loadedSummary.append("Loaded Application Class: ${appClass.name}\n")
                }

                if (!entryActivityName.isNullOrBlank()) {
                    val actClass = classLoader.loadClass(entryActivityName)
                    loadedSummary.append("Verified Entry Activity Class: ${actClass.name}\n")
                }

                ContainerLaunchResult(
                    success = true,
                    packageName = app.packageName,
                    loadedClassesInfo = entryActivityName ?: appClassName ?: "DexClassLoader initialized",
                    details = loadedSummary.toString().trim()
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error launching ${app.packageName} in shared process", t)
                ContainerLaunchResult(
                    success = false,
                    packageName = app.packageName,
                    loadedClassesInfo = "Error",
                    details = "Failed to load APK in shared process: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
}
