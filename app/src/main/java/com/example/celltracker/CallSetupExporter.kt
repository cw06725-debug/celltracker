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
        val networkCsv=detail.item.recordingPath?.let{rp->File(rp).takeIf{it.exists()}?.let{f->save(context,"${base}_network_recording.csv","text/csv",f.readBytes()).toString()}}
        val htmlName="${base}_report.html";val html=save(context,htmlName,"text/html",buildHtml(detail).toByteArray()).toString()
        val xlsxName="${base}_report.xlsx";val xlsx=save(context,xlsxName,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",buildXlsx(dir,detail)).toString()
        val kmlName="${base}_track.kml";val kml=save(context,kmlName,"application/vnd.google-earth.kml+xml",buildKml(detail).toByteArray()).toString()
        return ExportResult("Call Setup report exported · HTML Report + Excel + CSV + KML",listOfNotNull(csv,networkCsv),html,htmlName,xlsx,xlsxName,kml,kmlName)
    }

    private fun buildCombinedCsv(d:CallSetupDetail)=buildString {
        appendLine("attempt_number,attempt_id,direction,result,confidence,setup_latency_ms,endpoint,moment,timestamp,subscription_id,sim_slot,operator,rat,display_rat,voice_rat,mcc,mnc,tac,cell_id,pci,arfcn,band,bandwidth,rsrp,rsrq,sinr,rssi,ca_endc,data_network,latitude,longitude,speed_kmh,accuracy")
        d.attempts.forEach{a->a.snapshots.forEach{s->appendLine(CallSetupRepository.csv(listOf(a.attemptNumber,a.attemptId,a.direction,a.result,a.confidence,a.setupLatencyMs?:"",s.endpoint,s.moment,s.timestampMs,s.subscriptionId,s.simSlot,s.operator,s.rat,s.displayRat,s.voiceRat,s.mcc,s.mnc,s.tac,s.cellId,s.pci,s.arfcn,s.band,s.bandwidth,s.rsrp,s.rsrq,s.sinr,s.rssi,s.carrierAggregation,s.dataNetwork,s.latitude?:"",s.longitude?:"",s.speedKmh,s.accuracy)))}}
    }

    private fun buildHtml(d:CallSetupDetail):String {
        val i=d.item
        fun e(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        fun l(v:Double?)=v?.let{String.format(Locale.US,"%.1f ms",it)}?:"--"
        fun dt(v:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.getDefault()).format(Date(v))

        // Prefer the actual attempt rows for report KPIs so the exported Summary stays
        // consistent with the table even if older session metadata was partially written.
        val attempts=d.attempts
        val total=attempts.size
        val success=attempts.count{it.result=="SUCCESS"}
        val failure=(total-success).coerceAtLeast(0)
        val successRate=if(total==0)0.0 else success*100.0/total
        val lat=attempts.mapNotNull{it.setupLatencyMs?.toDouble()}.sorted()
        val avg=lat.takeIf{it.isNotEmpty()}?.average()
        fun pct(q:Double)=lat.takeIf{it.isNotEmpty()}?.get(kotlin.math.ceil((lat.size-1)*q).toInt().coerceIn(0,lat.lastIndex))

        fun metric(label:String,value:String)=
            "<div class='metric'><div class='k'>${e(label)}</div><div class='v'>${e(value)}</div></div>"

        return buildString {
            append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
            append("<style>")
            append("*{box-sizing:border-box}body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;margin:0;background:#f6f7fb;color:#1f2937}")
            append(".wrap{max-width:980px;margin:0 auto;padding:16px}.title{font-size:26px;font-weight:750;margin:4px 0 16px}")
            append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:14px;padding:14px;margin:12px 0;box-shadow:0 1px 2px rgba(0,0,0,.03)}")
            append(".section{font-size:17px;font-weight:700;margin:0 0 10px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}")
            append(".metric{background:#f9fafb;border-radius:10px;padding:10px;min-width:0}.k{color:#667085;font-size:12px;margin-bottom:3px}.v{font-weight:650;overflow-wrap:anywhere}")
            append(".tablewrap{overflow-x:auto;-webkit-overflow-scrolling:touch}table{border-collapse:collapse;width:100%;min-width:700px;font-size:13px}")
            append("th,td{padding:8px 9px;border-bottom:1px solid #e5e7eb;text-align:left;vertical-align:top;white-space:nowrap}th{background:#f9fafb;position:sticky;top:0}")
            append("td.detail{white-space:normal;min-width:180px}.ok{font-weight:700}.muted{color:#667085}")
            append("@media(max-width:560px){.wrap{padding:12px}.title{font-size:23px}.grid{grid-template-columns:1fr}.card{padding:12px}table{font-size:12px}}")
            append("</style></head><body><div class='wrap'>")
            append("<div class='title'>CellTracker Call Setup Summary</div>")

            append("<div class='card'><div class='section'>Session</div><div class='grid'>")
            append(metric("Task",i.taskName))
            append(metric("Direction",i.direction))
            append(metric("DUT A","${i.deviceA} / ${i.operatorA}"))
            append(metric("DUT B","${i.deviceB} / ${i.operatorB}"))
            append(metric("Start",dt(i.startedAt)))
            append(metric("End",if(i.endedAt>0)dt(i.endedAt) else "--"))
            append(metric("Status",i.status))
            append("</div></div>")

            append("<div class='card'><div class='section'>Performance</div><div class='grid'>")
            append(metric("Attempts",total.toString()))
            append(metric("Success / Failure","$success / $failure"))
            append(metric("Success Rate",String.format(Locale.US,"%.1f%%",successRate)))
            append(metric("Average Setup",l(avg)))
            append(metric("Min / Max","${l(lat.minOrNull())} / ${l(lat.maxOrNull())}"))
            append(metric("P50 / P90 / P95","${l(pct(.5))} / ${l(pct(.9))} / ${l(pct(.95))}"))
            append(metric("High Latency Events",d.events.count{it.type=="HIGH_CALL_SETUP_LATENCY"}.toString()))
            append(metric("Timeout Events",d.events.count{it.type=="CALL_SETUP_TIMEOUT"}.toString()))
            append(metric("Network Recording",if(d.networkSamples.isNotEmpty())"${d.networkSamples.size} samples" else "Not available"))
            append("</div></div>")

            append("<div class='card'><div class='section'>Attempts</div><div class='tablewrap'><table><tr><th>#</th><th>Direction</th><th>Result</th><th>Setup</th><th>Confidence</th><th>Detail</th></tr>")
            attempts.forEach{
                append("<tr><td>${it.attemptNumber}</td><td>${e(it.direction)}</td><td class='ok'>${e(it.result)}</td><td>${l(it.setupLatencyMs?.toDouble())}</td><td>${e(it.confidence)}</td><td class='detail'>${e(it.failureDetail.ifBlank{"--"})}</td></tr>")
            }
            append("</table></div></div>")

            append("<div class='card'><div class='section'>Events</div><div class='tablewrap'><table><tr><th>Time</th><th>Type</th><th>Direction</th><th>Attempt</th><th>Detail</th></tr>")
            d.events.forEach{ev->append("<tr><td>${e(dt(ev.timestampMs))}</td><td>${e(ev.type)}</td><td>${e(ev.direction)}</td><td>${e(ev.attemptId)}</td><td class='detail'>${e(ev.detail.ifBlank{"--"})}</td></tr>")}
            append("</table></div></div>")
            append("</div></body></html>")
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
        val networkRows=d.item.recordingPath?.let{rp->File(rp).takeIf{it.exists()}?.readLines()?.filter{it.isNotBlank()}?.map(CallSetupRepository::parseCsv)}?:emptyList()
        val sheets=mutableListOf("Summary" to summary,"Attempts" to rows("attempts.csv"),"MO Snapshots" to mo,"MT Snapshots" to mt,"Events" to rows("events.csv"))
        if(networkRows.isNotEmpty()) sheets.add("Network Recording" to networkRows)
        return PingExporter.simpleXlsx(sheets)
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
