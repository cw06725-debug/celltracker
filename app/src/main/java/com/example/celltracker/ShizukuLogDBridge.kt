package com.example.celltracker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
            .daemon(false).processNameSuffix("ct_diagnostics").tag("ct_log_diagnostics").version(1)
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
    suspend fun probeLogFolder(context: Context): Result<String> = perform(context, 2).also { result ->
        state.value = state.value.copy(logDirectoryProbe = result.getOrElse { "Not readable: ${it.message}" })
    }
}
