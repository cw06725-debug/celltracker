package com.example.celltracker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.MediaStore
import java.util.Base64
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Optional shell-identity transport: never changes system properties without a user click. */
data class ShizukuAccessState(
    val status: String = "Not checked",
    val authorized: Boolean = false,
    val uid: Int? = null,
    val detail: String = "",
    val logDirectoryProbe: String = "Not checked"
)

object ShizukuLogDBridge {
    val state = MutableStateFlow(ShizukuAccessState())
    private const val REQUEST_CODE = 12970
    private val lock = Any()
    @Volatile private var initialized = false
    @Volatile private var service: IShizukuDiagnostics? = null
    private var serviceAwaiter: CompletableDeferred<IShizukuDiagnostics>? = null

    private val receiver = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener {
        synchronized(lock) {
            service = null
            serviceAwaiter?.cancel()
            serviceAwaiter = null
        }
        state.value = ShizukuAccessState(status = "Service stopped", detail = "Restart Shizuku after reboot")
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            refresh()
            if (grantResult != PackageManager.PERMISSION_GRANTED)
                state.value = state.value.copy(detail = "Permission denied. Check Shizuku authorization.")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val proxy = IShizukuDiagnostics.Stub.asInterface(binder)
            if (proxy != null) {
                service = proxy
                synchronized(lock) { serviceAwaiter?.complete(proxy) }
            } else {
                synchronized(lock) { serviceAwaiter?.completeExceptionally(IllegalStateException("No Shizuku service binder")) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            synchronized(lock) { serviceAwaiter = null }
        }
    }

    fun initialize() {
        synchronized(lock) {
            if (initialized) return
            initialized = true
        }
        Shizuku.addBinderReceivedListenerSticky(receiver)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }

    fun refresh() {
        val current = runCatching {
            if (!Shizuku.pingBinder()) {
                ShizukuAccessState(status = "Service not running", detail = "Start Shizuku first")
            } else if (Shizuku.isPreV11()) {
                ShizukuAccessState(status = "Unsupported Shizuku version", detail = "Update Shizuku")
            } else {
                val uid = Shizuku.getUid()
                val allowed = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ShizukuAccessState(
                    status = if (allowed) "Ready" else "Authorization required",
                    authorized = allowed,
                    uid = uid,
                    detail = if (allowed) (if (uid == 2000) "ADB shell access" else if (uid == 0) "Root access" else "UID $uid") else "Authorize CellTracker in Shizuku",
                    logDirectoryProbe = state.value.logDirectoryProbe
                )
            }
        }.getOrElse { ShizukuAccessState(status = "Unavailable", detail = it.message ?: "Shizuku not installed or started") }
        state.value = current
    }

    fun requestAuthorization(): Result<Unit> = runCatching {
        initialize()
        check(Shizuku.pingBinder()) { "Start the Shizuku service first" }
        check(!Shizuku.isPreV11()) { "Update Shizuku to API 11 or later" }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            check(!Shizuku.shouldShowRequestPermissionRationale()) {
                "Permission denied previously. Allow CellTracker inside Shizuku."
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } else refresh()
    }.onFailure { state.value = state.value.copy(detail = it.message ?: "Authorization failed") }

    fun isAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private suspend fun userService(context: Context): IShizukuDiagnostics {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        check(isAuthorized()) { "Shizuku is not running or CellTracker is not authorized" }
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuDiagnosticsService::class.java))
            .daemon(false).processNameSuffix("ct_diagnostics").tag("ct_log_diagnostics").version(2)
        val (awaiter, shouldBind) = synchronized(lock) {
            val previous = serviceAwaiter
            if (previous != null && previous.isActive) previous to false
            else CompletableDeferred<IShizukuDiagnostics>().also { serviceAwaiter = it } to true
        }
        if (shouldBind) {
            try {
                withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            } catch (e: Exception) {
                synchronized(lock) { if (serviceAwaiter === awaiter) serviceAwaiter = null }
                awaiter.completeExceptionally(e)
            }
        }
        return try {
            withTimeout(15_000) { awaiter.await() }
        } catch (e: Exception) {
            synchronized(lock) { if (serviceAwaiter === awaiter) serviceAwaiter = null }
            throw e
        }
    }

    private suspend fun perform(context: Context, operation: Int, value: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val reply = userService(context.applicationContext).perform(operation, value)
                check(reply.startsWith("OK\n")) { reply.removePrefix("ERROR\n") }
                reply.removePrefix("OK\n")
            }.onFailure { state.value = state.value.copy(detail = "Shizuku operation failed: ${it.message}") }
        }

    suspend fun readLogD(context: Context): Result<String> = perform(context, 0)
    suspend fun writeLogD(context: Context, value: String): Result<String> = perform(context, 1, value)
    /** The original directory stays untouched; write only to a fresh Downloads ZIP. */
    suspend fun exportLogZip(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val svc = userService(context.applicationContext)
                val manifest = svc.listLogFiles()
                check(manifest.startsWith("OK\n")) { manifest.removePrefix("ERROR\n") }
                data class Item(val path: String, val size: Long, val modified: Long)
                val items = manifest.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
                    val columns = line.split('\t')
                    check(columns.size == 3) { "Malformed Shizuku file index" }
                    val path = String(Base64.getUrlDecoder().decode(columns[0]), Charsets.UTF_8)
                    Item(path, columns[1].toLong(), columns[2].toLong())
                }.toList()
                check(items.isNotEmpty()) { "No source files" }
                val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = "CellTracker_Shizuku_DebugLogger_$date.zip"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Vendor Logs")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not allocate Downloads export")
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            for ((index, item) in items.withIndex()) {
                                check(item.size in 0..(256L * 1024 * 1024)) { "Invalid log size" }
                                check(!item.path.startsWith("/") && !item.path.split('/').any { it == ".." }) { "Invalid logger path" }
                                zip.putNextEntry(ZipEntry(item.path))
                                var offset = 0L
                                while (offset < item.size) {
                                    val take = minOf(64 * 1024L, item.size - offset).toInt()
                                    val part = svc.readLogChunk(item.path, offset, take)
                                    check(part.size == take) { "Short read for ${item.path} at $offset" }
                                    zip.write(part)
                                    offset += part.size
                                }
                                zip.closeEntry()
                                withContext(Dispatchers.Main) { onProgress(index + 1, items.size) }
                            }
                        }
                    } ?: error("Cannot open ZIP output")
                    if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    uri
                } catch (e: Exception) {
                    context.contentResolver.delete(uri, null, null)
                    throw e
                }
            }.onFailure { state.value = state.value.copy(detail = "Shizuku export failed: ${it.message}") }
        }

    suspend fun probeLogFolder(context: Context): Result<String> = perform(context, 2).also { result ->
        state.value = state.value.copy(logDirectoryProbe = result.getOrElse { "Not readable: ${it.message}" })
    }
}
