package com.example.celltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
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
        return subscriptions.map { sub ->
            val tm = baseTm.createForSubscriptionId(sub.subscriptionId)
            val label = sub.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${sub.simSlotIndex + 1}"
            readForTelephonyManager(tm, sub.subscriptionId, sub.simSlotIndex, label)
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

        val cells = requestFreshCells(tm)
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
        val serving = servingRaw.copy(displayRat = displayRat)
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
                sinr = intDbValue(s.ssSinr),
                registered = cell.isRegistered
            )
        }
        else -> null
    }

    private fun intValue(v: Int): String = if (v == CellInfo.UNAVAILABLE) "--" else v.toString()
    private fun longValue(v: Long): String = if (v == CellInfo.UNAVAILABLE.toLong()) "--" else v.toString()
    private fun intDbValue(v: Int): String = if (v == CellInfo.UNAVAILABLE) "--" else v.toString()
}
