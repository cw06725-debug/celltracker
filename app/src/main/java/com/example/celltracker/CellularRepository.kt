package com.example.celltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthLte
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class CellularRepository(private val context: Context) {
    private val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    fun activeSubscriptions(): List<SubscriptionInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            subscriptionManager.activeSubscriptionInfoList?.sortedBy { it.simSlotIndex } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    suspend fun readAllSims(): List<SimCellState> {
        val subscriptions = activeSubscriptions()
        if (subscriptions.isEmpty()) {
            val single = readForTelephonyManager(baseTm, -1, -1, "SIM")
            return listOf(single)
        }
        return coroutineScope {
            subscriptions.map { sub ->
                async {
                    val tm = baseTm.createForSubscriptionId(sub.subscriptionId)
                    val label = sub.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${sub.simSlotIndex + 1}"
                    readForTelephonyManager(tm, sub.subscriptionId, sub.simSlotIndex, label)
                }
            }.awaitAll()
        }
    }

    private suspend fun readForTelephonyManager(
        tm: TelephonyManager,
        subscriptionId: Int,
        simSlotIndex: Int,
        simLabel: String
    ): SimCellState {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return SimCellState(subscriptionId, simSlotIndex, simLabel)
        }

        // Some vendor RILs delay requestCellInfoUpdate callbacks for many seconds.
        // Do not let one slow callback stall the whole UI refresh loop; fall back to
        // the framework's cached allCellInfo snapshot after a short timeout.
        val cells = withTimeoutOrNull(900L) { requestFreshCells(tm) }
            ?: runCatching { tm.allCellInfo ?: emptyList() }.getOrDefault(emptyList())
        val parsed = cells.mapNotNull { parseCell(it, tm, subscriptionId, simSlotIndex, simLabel) }
        val registered = parsed.filter { it.registered }
        val nrVisible = parsed.any { it.rat == "NR" }
        val servingRaw = registered.firstOrNull { it.rat == "NR" }
            ?: registered.firstOrNull { it.rat == "LTE" }
            ?: registered.firstOrNull()
            ?: parsed.firstOrNull()
            ?: CellData(subscriptionId = subscriptionId, simSlotIndex = simSlotIndex, simLabel = simLabel)

        val displayRat = when {
            servingRaw.rat == "NR" -> "5G NR (SA/NR)"
            servingRaw.rat == "LTE" && nrVisible -> "5G NSA (LTE anchor)"
            servingRaw.rat == "LTE" -> "LTE"
            else -> servingRaw.rat
        }
        val serving = if (servingRaw.rat == "LTE" && servingRaw.sinr == "--") {
            servingRaw.copy(
                displayRat = displayRat,
                sinr = readLteSinrFallback(tm)
            )
        } else {
            servingRaw.copy(displayRat = displayRat)
        }
        val neighbors = parsed.filterNot { it.registered }.map { neighbor ->
            neighbor.copy(displayRat = if (neighbor.rat == "NR") "NR" else neighbor.rat)
        }
        return SimCellState(subscriptionId, simSlotIndex, simLabel, serving, neighbors)
    }

    private suspend fun requestFreshCells(tm: TelephonyManager): List<CellInfo> = suspendCancellableCoroutine { cont ->
        try {
            tm.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    if (cont.isActive) cont.resume(cellInfo)
                }
                override fun onError(errorCode: Int, detail: Throwable?) {
                    val fallback = try { tm.allCellInfo ?: emptyList() } catch (_: Exception) { emptyList() }
                    if (cont.isActive) cont.resume(fallback)
                }
            })
        } catch (_: Exception) {
            val fallback = try { tm.allCellInfo ?: emptyList() } catch (_: Exception) { emptyList() }
            if (cont.isActive) cont.resume(fallback)
        }
    }

    private fun parseCell(
        cell: CellInfo,
        tm: TelephonyManager,
        subscriptionId: Int,
        simSlotIndex: Int,
        simLabel: String
    ): CellData? = when (cell) {
        is CellInfoLte -> {
            val id = cell.cellIdentity
            val s = cell.cellSignalStrength
            CellData(
                subscriptionId = subscriptionId,
                simSlotIndex = simSlotIndex,
                simLabel = simLabel,
                rat = "LTE",
                displayRat = "LTE",
                operator = tm.networkOperatorName.ifBlank { simLabel },
                mcc = id.mccString ?: "--",
                mnc = id.mncString ?: "--",
                tac = intValue(id.tac),
                cellId = intValue(id.ci),
                pci = intValue(id.pci),
                arfcn = intValue(id.earfcn),
                rsrp = intDbValue(s.rsrp),
                rsrq = intDbValue(s.rsrq),
                sinr = intDbValue(s.rssnr),
                band = if (android.os.Build.VERSION.SDK_INT >= 30) bandValue(id.bands) else lteBandFromEarfcn(id.earfcn),
                bandwidth = bandwidthValue(id.bandwidth),
                rssi = intDbValue(s.rssi),
                timingAdvance = intValue(s.timingAdvance),
                registered = cell.isRegistered
            )
        }
        is CellInfoNr -> {
            val id = cell.cellIdentity as CellIdentityNr
            val s = cell.cellSignalStrength as CellSignalStrengthNr
            CellData(
                subscriptionId = subscriptionId,
                simSlotIndex = simSlotIndex,
                simLabel = simLabel,
                rat = "NR",
                displayRat = "NR",
                operator = tm.networkOperatorName.ifBlank { simLabel },
                mcc = id.mccString ?: "--",
                mnc = id.mncString ?: "--",
                tac = intValue(id.tac),
                cellId = longValue(id.nci),
                pci = intValue(id.pci),
                arfcn = intValue(id.nrarfcn),
                rsrp = intDbValue(s.ssRsrp),
                rsrq = intDbValue(s.ssRsrq),
                sinr = intDbValue(s.ssSinr).takeIf { it != "--" } ?: intDbValue(s.csiSinr),
                band = if (android.os.Build.VERSION.SDK_INT >= 30) bandValue(id.bands, true) else nrBandFromArfcn(id.nrarfcn),
                csiRsrp = intDbValue(s.csiRsrp),
                csiRsrq = intDbValue(s.csiRsrq),
                csiSinr = intDbValue(s.csiSinr),
                registered = cell.isRegistered
            )
        }
        else -> null
    }

    /**
     * LTE SINR is inconsistently populated in CellInfo on some vendor builds.
     * Try the latest SignalStrength snapshot as a second public-API source, then
     * parse the framework/vendor toString as a compatibility fallback.
     */
    private fun readLteSinrFallback(tm: TelephonyManager): String {
        val latest = runCatching { tm.signalStrength }.getOrNull()
        val lte = runCatching { latest?.getCellSignalStrengths(CellSignalStrengthLte::class.java)?.firstOrNull() }.getOrNull()
        val direct = lte?.rssnr
        if (direct != null && direct != CellInfo.UNAVAILABLE) return direct.toString()

        // Some Transsion/vendor frameworks keep rssnr in the object string while
        // the public getter is reported unavailable. Use it only when it is a sane
        // LTE RSSNR value, and otherwise keep showing -- rather than invent data.
        val candidates = listOfNotNull(lte?.toString(), latest?.toString())
        val patterns = listOf(
            Regex("(?:rssnr|rssnrDb|sinr)\\s*[=:]\\s*(-?\\d+)", RegexOption.IGNORE_CASE),
            Regex("mRssnr\\s*=\\s*(-?\\d+)", RegexOption.IGNORE_CASE)
        )
        for (text in candidates) {
            for (pattern in patterns) {
                val value = pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                if (value in -20..30) return value.toString()
            }
        }
        return "--"
    }

    private fun intValue(v: Int): String = if (v == CellInfo.UNAVAILABLE) "--" else v.toString()
    private fun longValue(v: Long): String = if (v == CellInfo.UNAVAILABLE.toLong()) "--" else v.toString()
    private fun intDbValue(v: Int): String = if (v == CellInfo.UNAVAILABLE) "--" else v.toString()
    private fun bandValue(v: IntArray, nr: Boolean = false): String = if (v.isEmpty()) "--" else v.joinToString("/") { if (nr) "n$it" else "B$it" }
    private fun lteBandFromEarfcn(a: Int): String = when (a) {
        in 0..599 -> "B1"; in 600..1199 -> "B2"; in 1200..1949 -> "B3"; in 1950..2399 -> "B4"
        in 2400..2649 -> "B5"; in 2750..3449 -> "B7"; in 3450..3799 -> "B8"; in 6150..6449 -> "B20"
        in 9210..9659 -> "B28"; in 37750..38249 -> "B38"; in 38250..38649 -> "B39"; in 38650..39649 -> "B40"
        in 39650..41589 -> "B41"; else -> "--"
    }
    private fun nrBandFromArfcn(a: Int): String = when (a) {
        in 620000..653333 -> "n78"; in 499200..537999 -> "n41"; in 422000..434000 -> "n1"; else -> "--"
    }
    private fun bandwidthValue(khz: Int): String = if (khz == CellInfo.UNAVAILABLE || khz <= 0) "--" else String.format(java.util.Locale.US, "%.1f MHz", khz / 1000.0)
}

