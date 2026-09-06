package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CallSetupExporter {
    fun export(context: Context, path: String): ExportResult {
        val dir=File(path);require(dir.isDirectory){"Call Setup session not found"}
        val detail=CallSetupRepository(context).loadDetail(path)?:error("Unable to load Call Setup session")
        val base=dir.name
        val combinedCsv=buildCombinedCsv(detail)
        val csvName="${base}_attempts_and_snapshots.csv";val csv=save(context,csvName,"text/csv",combinedCsv.toByteArray()).toString()
        val htmlName="${base}_summary.html";val html=save(context,htmlName,"text/html",buildHtml(detail).toByteArray()).toString()
        val xlsxName="${base}_report.xlsx";val xlsx=save(context,xlsxName,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",buildXlsx(dir,detail)).toString()
        val kmlName="${base}_track.kml";val kml=save(context,kmlName,"application/vnd.google-earth.kml+xml",buildKml(detail).toByteArray()).toString()
        return ExportResult("Call Setup export successful · CSV + Summary + Excel + KML",listOf(csv),html,htmlName,xlsx,xlsxName,kml,kmlName)
    }

    private fun buildCombinedCsv(d:CallSetupDetail)=buildString {
        appendLine("attempt_number,attempt_id,direction,result,confidence,setup_latency_ms,endpoint,moment,timestamp,subscription_id,sim_slot,operator,rat,display_rat,voice_rat,mcc,mnc,tac,cell_id,pci,arfcn,band,bandwidth,rsrp,rsrq,sinr,rssi,ca_endc,data_network,latitude,longitude,speed_kmh,accuracy")
        d.attempts.forEach{a->a.snapshots.forEach{s->appendLine(CallSetupRepository.csv(listOf(a.attemptNumber,a.attemptId,a.direction,a.result,a.confidence,a.setupLatencyMs?:"",s.endpoint,s.moment,s.timestampMs,s.subscriptionId,s.simSlot,s.operator,s.rat,s.displayRat,s.voiceRat,s.mcc,s.mnc,s.tac,s.cellId,s.pci,s.arfcn,s.band,s.bandwidth,s.rsrp,s.rsrq,s.sinr,s.rssi,s.carrierAggregation,s.dataNetwork,s.latitude?:"",s.longitude?:"",s.speedKmh,s.accuracy)))}}
    }

    private fun buildHtml(d:CallSetupDetail):String {
        val i=d.item
        fun e(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        fun l(v:Double?)=v?.let{String.format(Locale.US,"%.1f ms",it)}?:"--"
        val lat=d.attempts.mapNotNull{it.setupLatencyMs?.toDouble()}.sorted()
        fun pct(q:Double)=lat.takeIf{it.isNotEmpty()}?.get(kotlin.math.ceil((lat.size-1)*q).toInt().coerceIn(0,lat.lastIndex))
        return buildString {
        append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'><style>body{font-family:sans-serif;margin:18px;color:#1f2937}.g{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px}.c{border:1px solid #ddd;border-radius:12px;padding:12px;margin:10px 0}.k{color:#667085;font-size:12px}.v{font-weight:600}table{border-collapse:collapse;width:100%;font-size:13px}th,td{padding:7px;border-bottom:1px solid #ddd;text-align:left}</style></head><body><h1>CellTracker Call Setup Summary</h1><div class='c g'>")
        listOf("Task" to i.taskName,"DUT A" to "${i.deviceA} / ${i.operatorA}","DUT B" to "${i.deviceB} / ${i.operatorB}","Direction" to i.direction,"Attempts" to i.attempts.toString(),"Success / Failure" to "${i.success} / ${i.failure}","Success Rate" to String.format(Locale.US,"%.1f%%",i.successRate),"Avg / Min / Max" to "${l(i.averageMs)} / ${l(lat.minOrNull())} / ${l(lat.maxOrNull())}","P50 / P90 / P95" to "${l(pct(.5))} / ${l(i.p90Ms)} / ${l(i.p95Ms)}","HIGH latency" to d.events.count{it.type=="HIGH_CALL_SETUP_LATENCY"}.toString(),"Timeout" to d.events.count{it.type=="CALL_SETUP_TIMEOUT"}.toString(),"Status" to i.status).forEach{(k,v)->append("<div><div class='k'>${e(k)}</div><div class='v'>${e(v)}</div></div>")}
        append("</div><div class='c'><table><tr><th>#</th><th>Direction</th><th>Result</th><th>Setup</th><th>Confidence</th><th>Detail</th></tr>")
        d.attempts.forEach{append("<tr><td>${it.attemptNumber}</td><td>${e(it.direction)}</td><td>${e(it.result)}</td><td>${l(it.setupLatencyMs?.toDouble())}</td><td>${e(it.confidence)}</td><td>${e(it.failureDetail)}</td></tr>")}
        append("</table></div></body></html>")
        }
    }

    private fun buildXlsx(dir:File,d:CallSetupDetail):ByteArray {
        fun rows(name:String)=File(dir,name).takeIf{it.exists()}?.readLines()?.map(CallSetupRepository::parseCsv)?:emptyList()
        val lat=d.attempts.mapNotNull{it.setupLatencyMs?.toDouble()}.sorted()
        fun pct(q:Double)=lat.takeIf{it.isNotEmpty()}?.get(kotlin.math.ceil((lat.size-1)*q).toInt().coerceIn(0,lat.lastIndex))
        val summary=listOf(listOf("CellTracker Call Setup Summary"),listOf("Task",d.item.taskName),listOf("DUT A",d.item.deviceA),listOf("DUT B",d.item.deviceB),listOf("Operator A",d.item.operatorA),listOf("Operator B",d.item.operatorB),listOf("Direction",d.item.direction),listOf("Attempts",d.item.attempts.toString()),listOf("Success",d.item.success.toString()),listOf("Failure",d.item.failure.toString()),listOf("Success Rate %",String.format(Locale.US,"%.3f",d.item.successRate)),listOf("Average Setup ms",d.item.averageMs?.toString().orEmpty()),listOf("Minimum Setup ms",lat.minOrNull()?.toString().orEmpty()),listOf("Maximum Setup ms",lat.maxOrNull()?.toString().orEmpty()),listOf("P50 ms",pct(.5)?.toString().orEmpty()),listOf("P90 ms",d.item.p90Ms?.toString().orEmpty()),listOf("P95 ms",d.item.p95Ms?.toString().orEmpty()),listOf("High threshold ms",d.item.highLatencyThresholdMs.toString()),listOf("HIGH_CALL_SETUP_LATENCY",d.events.count{it.type=="HIGH_CALL_SETUP_LATENCY"}.toString()),listOf("CALL_SETUP_TIMEOUT",d.events.count{it.type=="CALL_SETUP_TIMEOUT"}.toString()),listOf("Status",d.item.status))
        val header=CallSetupRepository.SNAPSHOT_HEADER.split(',')
        fun snapshotRow(attemptId:String,s:CallNetworkSnapshot)=listOf(attemptId,s.endpoint,s.moment,s.timestampMs.toString(),s.elapsedRealtimeMs.toString(),s.subscriptionId.toString(),s.simSlot.toString(),s.operator,s.rat,s.displayRat,s.voiceRat,s.mcc,s.mnc,s.tac,s.cellId,s.pci,s.arfcn,s.band,s.bandwidth,s.rsrp,s.rsrq,s.sinr,s.rssi,s.carrierAggregation,s.dataNetwork,s.latitude?.toString().orEmpty(),s.longitude?.toString().orEmpty(),s.speedKmh,s.accuracy)
        val mo=mutableListOf(header);val mt=mutableListOf(header)
        d.attempts.forEach { a -> a.snapshots.forEach { s ->
            val isMo=(a.direction=="A_TO_B"&&s.endpoint=="A")||(a.direction=="B_TO_A"&&s.endpoint=="B")
            (if(isMo)mo else mt).add(snapshotRow(a.attemptId,s))
        } }
        return PingExporter.simpleXlsx(listOf("Summary" to summary,"Attempts" to rows("attempts.csv"),"MO Snapshots" to mo,"MT Snapshots" to mt,"Events" to rows("events.csv")))
    }

    private fun buildKml(d:CallSetupDetail):String {
        fun e(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        val points=d.attempts.flatMap{a->a.snapshots.map{s->a to s}}.filter{it.second.latitude!=null&&it.second.longitude!=null}
        return buildString {
            append("<?xml version='1.0' encoding='UTF-8'?><kml xmlns='http://www.opengis.net/kml/2.2'><Document><name>${e(d.item.taskName)}</name><Style id='track'><LineStyle><color>ffffa500</color><width>4</width></LineStyle></Style>")
            listOf("A","B").forEach { ep -> val p=points.filter{it.second.endpoint==ep};if(p.isNotEmpty()){append("<Placemark><name>DUT $ep Track</name><styleUrl>#track</styleUrl><LineString><tessellate>1</tessellate><coordinates>");p.forEach{append("${it.second.longitude},${it.second.latitude},0 ")};append("</coordinates></LineString></Placemark>")} }
            points.filter{it.second.moment in listOf("CONNECTED","FAILURE")}.forEach{(a,s)->append("<Placemark><name>${e(a.result)} #${a.attemptNumber}</name><description>${e("${s.endpoint} ${s.moment} ${s.displayRat} RSRP ${s.rsrp}")}</description><Point><coordinates>${s.longitude},${s.latitude},0</coordinates></Point></Placemark>")}
            append("</Document></kml>")
        }
    }

    private fun save(c:Context,name:String,mime:String,bytes:ByteArray):android.net.Uri {
        val v=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,mime);if(Build.VERSION.SDK_INT>=29)put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/CellTracker")}
        val u=c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Unable to create export")
        c.contentResolver.openOutputStream(u)?.use{it.write(bytes)}?:error("Unable to write export")
        return u
    }
}
