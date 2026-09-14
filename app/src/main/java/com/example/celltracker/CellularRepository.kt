package com.example.celltracker

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthLte
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class CellularRepository(private val context: Context) {
    // LTE_CA is hidden from some public Android SDK stubs; AOSP uses network type 19.
    // Keep this compatibility value local so the project compiles with compileSdk 34.
    private val NETWORK_TYPE_LTE_CA_COMPAT = 19
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
        val parsedMutable = cells.mapNotNull { parseCell(it, tm, subscriptionId, simSlotIndex, simLabel) }.toMutableList()
        val displayInfo = withTimeoutOrNull(450L) { readDisplayInfo(tm) }
        val nrOverrideActive = isNrNsaOverride(displayInfo)
        val nrSignal = readNrSignalFallback(tm)

        // IMPORTANT: do not fabricate a CellInfoNr identity from SignalStrength alone.
        // SignalStrength can prove that NR signal metrics are exposed, but it does not
        // provide NR-ARFCN / PCI / NCI / Band. Keep those fields unavailable unless a
        // public identity source actually reports them.
        val parsed = parsedMutable.toList()
        val registered = parsed.filter { it.registered }
        val nrDirectServing = parsed.firstOrNull {
            it.rat == "NR" && (it.registered || it.connectionStatus == "SECONDARY_SERVING")
        }
        val servingRaw = registered.firstOrNull { it.rat == "NR" }
            ?: registered.firstOrNull { it.rat == "LTE" }
            ?: registered.firstOrNull()
            ?: parsed.firstOrNull { it.connectionStatus == "PRIMARY_SERVING" }
            ?: parsed.firstOrNull()
            ?: CellData(subscriptionId = subscriptionId, simSlotIndex = simSlotIndex, simLabel = simLabel)

        // For NSA, a visible NR neighbor is NOT enough to claim an active 5G bearer.
        // We only mark NSA active when TelephonyDisplayInfo says NR_NSA/NR_ADVANCED or
        // when CellInfo reports an NR secondary-serving cell.
        val nrNsaActive = servingRaw.rat == "LTE" && (nrOverrideActive || nrDirectServing != null)
        val displayRat = when {
            servingRaw.rat == "NR" -> "5G NR (SA/NR)"
            nrNsaActive -> "5G NSA (LTE anchor)"
            servingRaw.rat == "LTE" -> "LTE"
            else -> servingRaw.rat
        }
        val simPlmn = splitPlmn(runCatching { tm.simOperator }.getOrDefault(""))
        val registeredPlmn = splitPlmn(runCatching { tm.networkOperator }.getOrDefault(""))
        val commonBase = servingRaw.copy(
            displayRat = displayRat,
            simMcc = simPlmn.first,
            simMnc = simPlmn.second,
            registeredMcc = registeredPlmn.first,
            registeredMnc = registeredPlmn.second,
            dataRat = networkTypeName(runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)),
            voiceRat = networkTypeName(runCatching { tm.voiceNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)),
            roaming = runCatching { if (tm.isNetworkRoaming) "Yes" else "No" }.getOrDefault("--")
        )

        val nrState = when {
            servingRaw.rat == "NR" -> "SA_CONNECTED"
            nrNsaActive -> "NSA_CONNECTED"
            parsed.any { it.rat == "NR" } -> "OBSERVED_NOT_SERVING"
            else -> "NOT_ACTIVE"
        }
        val nrObservedCells = parsed.filter { it.rat == "NR" }
        val observedArfcns = nrObservedCells.map { it.arfcn }.filter { it != "--" }.distinct()
        val observedBands = nrObservedCells.map { it.band }.filter { it != "--" }.distinct()
        val observedSingleArfcn = observedArfcns.singleOrNull()
        val observedBandForSingleArfcn = if (observedSingleArfcn != null) {
            val arfcnInt = observedSingleArfcn.toIntOrNull() ?: CellInfo.UNAVAILABLE
            nrBandVerified(arfcnInt, intArrayOf()).first
        } else "--"

        val nrConnection = if (nrDirectServing != null) {
            NrConnectionData(
                state = nrState,
                band = nrDirectServing.band,
                arfcn = nrDirectServing.arfcn,
                pci = nrDirectServing.pci,
                tac = nrDirectServing.tac,
                cellId = nrDirectServing.cellId,
                ssRsrp = nrDirectServing.rsrp.takeIf { it != "--" } ?: nrSignal?.first ?: "--",
                ssRsrq = nrDirectServing.rsrq.takeIf { it != "--" } ?: nrSignal?.second ?: "--",
                ssSinr = nrDirectServing.sinr.takeIf { it != "--" } ?: nrSignal?.third ?: "--",
                identitySource = if (nrDirectServing.registered) "CellInfoNr primary serving" else "CellInfoNr secondary serving",
                bandSource = nrDirectServing.bandSource
            )
        } else if (nrNsaActive && observedSingleArfcn != null) {
            // Some OEMs expose NR measurements in CellInfoNr but do not mark the
            // active NR leg as SECONDARY_SERVING. The common observed NR-ARFCN is
            // still a directly reported measurement, so it is safe to expose it
            // as OBSERVED data. Do not copy a PCI/NCI/TAC because we cannot prove
            // which observed NR cell is the active secondary-serving cell.
            NrConnectionData(
                state = nrState,
                band = observedBandForSingleArfcn,
                arfcn = observedSingleArfcn,
                ssRsrp = nrSignal?.first ?: "--",
                ssRsrq = nrSignal?.second ?: "--",
                ssSinr = nrSignal?.third ?: "--",
                identitySource = "NSA connected + common observed CellInfoNr NR-ARFCN; serving NR cell not identified",
                bandSource = if (observedBandForSingleArfcn != "--") "Observed NR-ARFCN compatible bands" else "--"
            )
        } else {
            NrConnectionData(
                state = nrState,
                ssRsrp = nrSignal?.first ?: "--",
                ssRsrq = nrSignal?.second ?: "--",
                ssSinr = nrSignal?.third ?: "--",
                identitySource = when {
                    nrNsaActive && nrObservedCells.isNotEmpty() -> "NSA connected; multiple/insufficient observed NR identities"
                    nrNsaActive && nrSignal != null -> "TelephonyDisplayInfo + NR SignalStrength; identity unavailable"
                    nrNsaActive -> "TelephonyDisplayInfo; NR identity unavailable"
                    else -> "--"
                },
                bandSource = "--"
            )
        }

        val caInfo = carrierAggregationInfo(tm, parsed, commonBase, nrNsaActive, nrConnection)
        val common = commonBase.copy(carrierAggregation = caInfo)
        val serving = if (common.rat == "LTE" && common.sinr == "--") {
            common.copy(sinr = readLteSinrFallback(tm))
        } else common

        // Secondary-serving NR is part of the active EN-DC connection, not a neighbor.
        val neighbors = parsed.filter { !it.registered && it.connectionStatus != "SECONDARY_SERVING" }.map { neighbor ->
            neighbor.copy(displayRat = if (neighbor.rat == "NR") "NR" else neighbor.rat)
        }
        val nrObservations = nrObservedCells.map { n ->
            NrObservation(
                connectionStatus = n.connectionStatus, registered = n.registered, band = n.band, arfcn = n.arfcn,
                pci = n.pci, tac = n.tac, cellId = n.cellId, ssRsrp = n.rsrp, ssRsrq = n.rsrq, ssSinr = n.sinr,
                bandSource = n.bandSource, arfcnSource = n.arfcnSource
            )
        }
        return SimCellState(subscriptionId, simSlotIndex, simLabel, serving, neighbors, nrConnection, nrObservations)
    }


    private fun resolveOperatorName(
        tm: TelephonyManager,
        cellMcc: String?,
        cellMnc: String?,
        fallback: String
    ): String {
        fun usable(value: String?): String? {
            val v = value?.trim().orEmpty()
            if (v.isBlank()) return null
            val normalized = v.lowercase().replace(" ", "")
            if (normalized.matches(Regex("sim\\d*")) ||
                normalized.matches(Regex("slot\\d*")) ||
                normalized == "unknown" || normalized == "null") return null
            return v
        }

        usable(runCatching { tm.networkOperatorName }.getOrNull())?.let { return it }
        usable(runCatching { tm.simOperatorName }.getOrNull())?.let { return it }

        // OEMs such as vivo may expose "SIM 1"/"SIM 2" as displayName.
        // carrierName is usually a better subscription-level fallback, but still
        // pass it through usable() so slot labels never become operator names.
        val subInfo = runCatching {
            activeSubscriptions().firstOrNull { it.subscriptionId == tm.subscriptionId }
        }.getOrNull()
        usable(subInfo?.carrierName?.toString())?.let { return it }
        usable(subInfo?.displayName?.toString())?.let { return it }

        val networkNumeric = runCatching { tm.networkOperator }.getOrNull().orEmpty()
        operatorFromPlmn(networkNumeric)?.let { return it }
        val cellPlmn = if (!cellMcc.isNullOrBlank() && !cellMnc.isNullOrBlank()) cellMcc + cellMnc else ""
        operatorFromPlmn(cellPlmn)?.let { return it }
        val simNumeric = runCatching { tm.simOperator }.getOrNull().orEmpty()
        operatorFromPlmn(simNumeric)?.let { return it }

        return fallback
    }

    private fun operatorFromPlmn(plmnRaw: String): String? {
        val plmn = plmnRaw.filter { it.isDigit() }
        return when (plmn) {
            "41001", "41007", "4101", "4107" -> "Jazz"
            "41003", "4103" -> "Ufone"
            "41004", "4104" -> "Zong"
            "41006", "4106" -> "Telenor"
            else -> null
        }
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
                operator = resolveOperatorName(tm, id.mccString, id.mncString, simLabel),
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
                cqi = intValue(s.cqi),
                level = intValue(s.level),
                asuLevel = intValue(s.asuLevel),
                registered = cell.isRegistered,
                connectionStatus = connectionStatusName(cell.cellConnectionStatus),
                bandSource = if (android.os.Build.VERSION.SDK_INT >= 30 && id.bands.isNotEmpty()) "CellIdentityLte.bands" else if (id.earfcn != CellInfo.UNAVAILABLE) "EARFCN mapping" else "--",
                arfcnSource = if (id.earfcn != CellInfo.UNAVAILABLE) "CellIdentityLte.earfcn" else "--"
            )
        }
        is CellInfoNr -> {
            val id = cell.cellIdentity as CellIdentityNr
            val s = cell.cellSignalStrength as CellSignalStrengthNr
            val verifiedBand = nrBandVerified(id.nrarfcn, if (android.os.Build.VERSION.SDK_INT >= 30) id.bands else intArrayOf())
            CellData(
                subscriptionId = subscriptionId,
                simSlotIndex = simSlotIndex,
                simLabel = simLabel,
                rat = "NR",
                displayRat = "NR",
                operator = resolveOperatorName(tm, id.mccString, id.mncString, simLabel),
                mcc = id.mccString ?: "--",
                mnc = id.mncString ?: "--",
                tac = intValue(id.tac),
                cellId = longValue(id.nci),
                pci = intValue(id.pci),
                arfcn = intValue(id.nrarfcn),
                rsrp = intDbValue(s.ssRsrp),
                rsrq = intDbValue(s.ssRsrq),
                sinr = intDbValue(s.ssSinr).takeIf { it != "--" } ?: intDbValue(s.csiSinr),
                band = verifiedBand.first,
                csiRsrp = intDbValue(s.csiRsrp),
                csiRsrq = intDbValue(s.csiRsrq),
                csiSinr = intDbValue(s.csiSinr),
                level = intValue(s.level),
                asuLevel = intValue(s.asuLevel),
                registered = cell.isRegistered,
                connectionStatus = connectionStatusName(cell.cellConnectionStatus),
                bandSource = verifiedBand.second,
                arfcnSource = if (id.nrarfcn != CellInfo.UNAVAILABLE) "CellIdentityNr.nrarfcn" else "--"
            )
        }
        is CellInfoWcdma -> {
            val id = cell.cellIdentity
            val ss = cell.cellSignalStrength
            CellData(
                subscriptionId = subscriptionId,
                simSlotIndex = simSlotIndex,
                simLabel = simLabel,
                rat = "WCDMA",
                displayRat = "WCDMA",
                operator = resolveOperatorName(tm, id.mccString, id.mncString, simLabel),
                mcc = id.mccString ?: "--",
                mnc = id.mncString ?: "--",
                tac = intValue(id.lac),
                cellId = intValue(id.cid),
                pci = intValue(id.psc),
                arfcn = intValue(id.uarfcn),
                rssi = intDbValue(ss.dbm),
                level = intValue(ss.level),
                asuLevel = intValue(ss.asuLevel),
                registered = cell.isRegistered,
                connectionStatus = connectionStatusName(cell.cellConnectionStatus),
                arfcnSource = if (id.uarfcn != CellInfo.UNAVAILABLE) "CellIdentityWcdma.uarfcn" else "--"
            )
        }
        is CellInfoGsm -> {
            val id = cell.cellIdentity
            val ss = cell.cellSignalStrength
            CellData(
                subscriptionId = subscriptionId,
                simSlotIndex = simSlotIndex,
                simLabel = simLabel,
                rat = "GSM",
                displayRat = "GSM",
                operator = resolveOperatorName(tm, id.mccString, id.mncString, simLabel),
                mcc = id.mccString ?: "--",
                mnc = id.mncString ?: "--",
                tac = intValue(id.lac),
                cellId = intValue(id.cid),
                pci = intValue(id.bsic),
                arfcn = intValue(id.arfcn),
                rssi = intDbValue(ss.dbm),
                level = intValue(ss.level),
                asuLevel = intValue(ss.asuLevel),
                registered = cell.isRegistered,
                connectionStatus = connectionStatusName(cell.cellConnectionStatus),
                arfcnSource = if (id.arfcn != CellInfo.UNAVAILABLE) "CellIdentityGsm.arfcn" else "--"
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

    private fun carrierAggregationInfo(
        tm: TelephonyManager,
        parsed: List<CellData>,
        serving: CellData,
        nrNsaActive: Boolean,
        nrConnection: NrConnectionData
    ): String {
        val registeredLteBands = parsed.filter { it.registered && it.rat == "LTE" }.map { it.band }.filter { it != "--" }.distinct()
        val activeNrBands = parsed.filter {
            it.rat == "NR" && (it.registered || it.connectionStatus == "SECONDARY_SERVING")
        }.map { it.band }.filter { it != "--" }.distinct()
        val type = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val lteCa = type == NETWORK_TYPE_LTE_CA_COMPAT || registeredLteBands.size > 1
        return when {
            serving.rat == "LTE" && nrNsaActive -> {
                val anchor = serving.band.takeIf { it != "--" } ?: "LTE"
                when {
                    activeNrBands.isNotEmpty() -> "EN-DC: $anchor + ${activeNrBands.joinToString(" + ")}"
                    nrConnection.band != "--" -> "EN-DC: $anchor + ${nrConnection.band} (observed)"
                    else -> "EN-DC: $anchor + NR (band unavailable)"
                }
            }
            lteCa && registeredLteBands.isNotEmpty() -> "LTE CA: ${registeredLteBands.joinToString(" + ")}"
            lteCa -> "LTE CA: Active"
            else -> "--"
        }
    }

    private suspend fun readDisplayInfo(tm: TelephonyManager): TelephonyDisplayInfo? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        return suspendCancellableCoroutine { cont ->
            val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    runCatching { tm.unregisterTelephonyCallback(this) }
                    if (cont.isActive) cont.resume(info)
                }
            }
            try {
                tm.registerTelephonyCallback(context.mainExecutor, callback)
                cont.invokeOnCancellation { runCatching { tm.unregisterTelephonyCallback(callback) } }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun isNrNsaOverride(info: TelephonyDisplayInfo?): Boolean {
        if (info == null) return false
        return when (info.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> true
            else -> false
        }
    }

    private fun readNrSignalFallback(tm: TelephonyManager): Triple<String, String, String>? {
        val latest = runCatching { tm.signalStrength }.getOrNull() ?: return null
        val nr = runCatching { latest.getCellSignalStrengths(CellSignalStrengthNr::class.java).firstOrNull() }.getOrNull() ?: return null
        val rsrp = intDbValue(nr.ssRsrp)
        val rsrq = intDbValue(nr.ssRsrq)
        val sinr = intDbValue(nr.ssSinr).takeIf { it != "--" } ?: intDbValue(nr.csiSinr)
        return if (rsrp == "--" && rsrq == "--" && sinr == "--") null else Triple(rsrp, rsrq, sinr)
    }

    private fun splitPlmn(raw: String?): Pair<String, String> {
        val digits = raw.orEmpty().filter { it.isDigit() }
        if (digits.length !in 5..6) return "--" to "--"
        return digits.take(3) to digits.drop(3)
    }

    /**
     * Return a validated NR band value plus its source.
     *
     * Policy: never force a single band from ARFCN when the same frequency belongs
     * to overlapping NR operating bands. If Android reports id.bands, intersect it
     * with the ARFCN-compatible set when we have a verified overlap table. If the
     * framework-reported band conflicts with a physically incompatible ARFCN, reject
     * that reported value instead of displaying a wrong band.
     */
    private fun nrBandVerified(arfcn: Int, reportedBands: IntArray): Pair<String, String> {
        val reported = reportedBands.filter { it > 0 }.distinct().sorted()
        val candidates = nrBandsFromArfcn(arfcn)

        if (arfcn == CellInfo.UNAVAILABLE) {
            return if (reported.isNotEmpty()) {
                reported.joinToString("/") { "n$it" } to "CellIdentityNr.bands"
            } else "--" to "--"
        }

        if (candidates.isNotEmpty()) {
            val intersection = reported.filter { it in candidates }
            return when {
                intersection.isNotEmpty() -> intersection.joinToString("/") { "n$it" } to "CellIdentityNr.bands validated by NR-ARFCN"
                reported.isNotEmpty() -> candidates.joinToString("/") { "n$it" } to "NR-ARFCN compatible bands; conflicting reported band rejected"
                else -> candidates.joinToString("/") { "n$it" } to "NR-ARFCN compatible bands"
            }
        }

        // Outside the ranges we have explicitly validated, a direct Android band
        // report is still better than inventing a mapping. Otherwise stay unknown.
        return if (reported.isNotEmpty()) {
            reported.joinToString("/") { "n$it" } to "CellIdentityNr.bands"
        } else "--" to "--"
    }

    /**
     * Conservative downlink/TDD overlap table for the ranges currently needed by
     * CellTracker. Multiple entries are intentionally preserved instead of guessed.
     * Examples: 509070 -> n41/n90; 640000 -> n48/n77/n78.
     */
    private fun nrBandsFromArfcn(a: Int): List<Int> {
        if (a == CellInfo.UNAVAILABLE) return emptyList()
        val out = linkedSetOf<Int>()
        // 2496-2690 MHz TDD family. n38 and n7 overlap only in their own subranges.
        if (a in 499200..538000) { out += 41; out += 90 }
        if (a in 514000..524000) out += 38
        if (a in 524000..538000) out += 7

        // 3300-4200 MHz family. n48 overlaps n77/n78 in 3550-3700 MHz.
        if (a in 620000..680000) out += 77
        if (a in 620000..653333) out += 78
        if (a in 636667..646666) out += 48

        // 4400-5000 MHz.
        if (a in 693334..733333) out += 79
        return out.toList().sorted()
    }

    private fun connectionStatusName(status: Int): String = when (status) {
        CellInfo.CONNECTION_PRIMARY_SERVING -> "PRIMARY_SERVING"
        CellInfo.CONNECTION_SECONDARY_SERVING -> "SECONDARY_SERVING"
        CellInfo.CONNECTION_NONE -> "NONE"
        else -> "UNKNOWN"
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_LTE, NETWORK_TYPE_LTE_CA_COMPAT -> "LTE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO-A"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO-B"
        else -> "--"
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
    private fun bandwidthValue(khz: Int): String = if (khz == CellInfo.UNAVAILABLE || khz <= 0) "--" else String.format(java.util.Locale.US, "%.1f MHz", khz / 1000.0)
}
