package com.example.celltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CellularRepository(private val context: Context) {
    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    suspend fun readCells(): Pair<CellData, Int> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return CellData() to 0
        }

        val cells = requestFreshCells()
        val parsed = cells.mapNotNull(::parseCell)
        val serving = parsed.firstOrNull { it.registered } ?: parsed.firstOrNull() ?: CellData()
        val neighbors = parsed.count { !it.registered }
        return serving to neighbors
    }

    private suspend fun requestFreshCells(): List<CellInfo> = suspendCancellableCoroutine { cont ->
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

    private fun parseCell(cell: CellInfo): CellData? = when (cell) {
        is CellInfoLte -> {
            val id = cell.cellIdentity
            val s = cell.cellSignalStrength
            CellData(
                rat = "LTE",
                operator = tm.networkOperatorName.ifBlank { "--" },
                mcc = id.mccString ?: "--",
                mnc = id.mncString ?: "--",
                tac = intValue(id.tac),
                cellId = intValue(id.ci),
                pci = intValue(id.pci),
                arfcn = intValue(id.earfcn),
                rsrp = dbValue(s.rsrp, "dBm"),
                rsrq = dbValue(s.rsrq, "dB"),
                sinr = dbValue(s.rssnr, "dB"),
                registered = cell.isRegistered
            )
        }
        is CellInfoNr -> {
            val id = cell.cellIdentity as CellIdentityNr
            val s = cell.cellSignalStrength as CellSignalStrengthNr
            CellData(
                rat = "NR",
                operator = tm.networkOperatorName.ifBlank { "--" },
                mcc = id.mccString ?: "--",
                mnc = id.mncString ?: "--",
                tac = intValue(id.tac),
                cellId = longValue(id.nci),
                pci = intValue(id.pci),
                arfcn = intValue(id.nrarfcn),
                rsrp = dbValue(s.ssRsrp, "dBm"),
                rsrq = dbValue(s.ssRsrq, "dB"),
                sinr = dbValue(s.ssSinr, "dB"),
                registered = cell.isRegistered
            )
        }
        else -> null
    }

    private fun intValue(v: Int): String = if (v == CellInfo.UNAVAILABLE) "--" else v.toString()
    private fun longValue(v: Long): String = if (v == CellInfo.UNAVAILABLE.toLong()) "--" else v.toString()
    private fun dbValue(v: Int, unit: String): String = if (v == CellInfo.UNAVAILABLE) "--" else "$v $unit"
}
