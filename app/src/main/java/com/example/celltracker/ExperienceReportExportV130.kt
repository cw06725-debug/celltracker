package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Report output from real/manual-provenanced observations, retaining original source references. */
object ExperienceReportExportV130 {
    private fun fmt(x:Double?)=x?.let { String.format(Locale.US,"%.3f",it) }.orEmpty()
    private fun html(v:String)=v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun save(context:Context,name:String,mime:String,data:ByteArray,date:String):Uri {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/$date/Analysis")
        }
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Create export failed")
        try { context.contentResolver.openOutputStream(uri)?.use{it.write(data)}?:error("Write failed") }
        catch(e:Exception){context.contentResolver.delete(uri,null,null);throw e};return uri
    }
    private fun metric(id:String)=ExperienceMetrics.find(id)?.label ?: id
    private fun summaryCounts(decisions:List<ExperienceDecision>)=ExperienceVerdict.values().associateWith{v->decisions.count{it.finalVerdict==v}}
    private fun materialized(data:List<ExperienceObservation>,session:String)=data.filter{it.session==session}
    fun exportResults(context:Context,session:String,all:List<ExperienceObservation>,decisions:List<ExperienceDecision>,rules:List<ExperienceRule>,date:String,ruleVersion:String):Uri {
        val rows=materialized(all,session)
        val caseIds=mapOf("PING_AVG_MS" to "APP-PING","PING_SUCCESS" to "APP-PING",
            "MFT_PING_SUCCESS" to "SIG-01", "MFT_PING_AVG_MS" to "SIG-01", "MFT_SHORT_VIDEO_AVG_MS" to "DL-01",
            "TIKTOK_STALLS" to "DL-02","TIKTOK_STALL_SEC" to "DL-02",
            "YOUTUBE_AVG_MS" to "DL-03","TIKTOK_UPLOAD_AVG_MS" to "UL-01","TIKTOK_UPLOAD_MAX_MS" to "UL-01",
            "WHATSAPP_SEND_AVG_MS" to "UL-02","CALL_SUCCESS" to "CALL-GENERIC","CALL_SETUP_AVG_MS" to "CALL-GENERIC",
            "VOLTE_SUCCESS" to "CALL-01","WHATSAPP_CALL_SUCCESS" to "CALL-02","VOLTE_LONG_SUCCESS" to "CALL-03",
            "RECOVERY_MS" to "COV-01")
        val resultHeader=listOf("Date","Scenario","Site / Route","Operator","Actual RAT","Test Case ID","Device","Role (DUT/REF)","Start Time","End Time","Attempts","Successes","Failures","Mean Latency (ms)","Mean Duration (s)","Max Duration (s)","RSRP (dBm)","Stall Count","Total Stall (s)","Audio / Experience Notes","Result","Bug ID","Report File / Link")
        val testResults= mutableListOf(resultHeader)
        rows.forEach{r->val m=ExperienceMetrics.find(r.metricId)?:return@forEach
            val d=decisions.firstOrNull{it.metricId==r.metricId && it.dutRole==r.role}
            val status=if(r.role.startsWith("DUT"))d?.finalVerdict?.name.orEmpty() else "REFERENCE"
            testResults+=listOf(r.date,r.scenario,"",r.operator,r.rat,caseIds[r.metricId].orEmpty(),r.role,r.role,
                if(r.dataOrigin=="AUTO_LOCAL")SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date(r.recordedAt)) else "","",r.attempts.toString(),"","",
                if(m.unit=="ms")fmt(r.value) else "",if(m.unit=="s")fmt(r.value) else "","",r.rsrp,
                if(r.metricId=="TIKTOK_STALLS")fmt(r.value) else "",if(r.metricId=="TIKTOK_STALL_SEC")fmt(r.value) else "",
                "${m.label}; ${r.dataOrigin}; Session=$session",status,d?.bugId.orEmpty(),r.source)
        }
        val comparisonHeader=listOf("Date","Scenario","Operator","RAT","Test Case ID","Metric & Unit","DUT Model","DUT Value","REF Model","REF Value","Difference (DUT-REF)","Relative Difference (%)","Direction (Lower/Higher Better)","Assessment","Bug ID","Evidence / Notes")
        val compare=mutableListOf(comparisonHeader)
        decisions.filter{it.dutValue!=null || it.refValue!=null}.forEach{d->
            val m=ExperienceMetrics.find(d.metricId)?:return@forEach;val src=rows.firstOrNull{it.role==d.dutRole&&it.metricId==d.metricId}
            val refMean=d.refValue
            val diff=if(d.dutValue!=null&&refMean!=null)d.dutValue-refMean else null
            val pct=if(diff!=null&&refMean!=null&&kotlin.math.abs(refMean)>0.0000001)diff/kotlin.math.abs(refMean)*100 else null
            compare+=listOf(src?.date.orEmpty(),src?.scenario.orEmpty(),src?.operator.orEmpty(),src?.rat.orEmpty(),caseIds[m.id].orEmpty(),"${m.label} (${m.unit})",
                d.dutRole,fmt(d.dutValue),"Comparable REF mean",fmt(refMean),fmt(diff),fmt(pct),m.direction.name,
                d.finalVerdict.name,d.bugId,"${d.reason}; rule=$ruleVersion; evidence=${d.evidenceIds.joinToString(";")}; override=${d.overrideReason}")
        }
        val summary=mutableListOf(listOf("Communication Experience Test Result"),listOf("Session ID",session),listOf("Date",date),
            listOf("Rule version",ruleVersion),listOf("Reference","Arithmetic mean of comparable REF1 and REF2 (when both present)"),
            listOf("Comparison","Same date, operator, scenario and actual/declared RAT required"),listOf("Source","AUTO_LOCAL vs IMPORTED_TEMPLATE vs MANUAL clearly indicated"))
        summary+=listOf("Metric","DUT Role","DUT","REF Mean","Difference","Difference Unit","AUTO","FINAL","Reason","Bug ID")
        decisions.filter{it.dutValue!=null||it.refValue!=null}.forEach{d->summary+=listOf(metric(d.metricId),d.dutRole,fmt(d.dutValue),fmt(d.refValue),fmt(d.difference),d.differenceUnit.name,d.automated.name,d.finalVerdict.name,d.reason,d.bugId)}
        val details=mutableListOf(listOf("Session","Date","Metric ID","Unit","Role","Value","Attempts","Operator","RAT","Scenario","Origin","Report Source","Band","PCI","RSRP","SINR"))
        rows.forEach{r->details+=listOf(r.session,r.date,r.metricId,ExperienceMetrics.find(r.metricId)?.unit.orEmpty(),r.role,fmt(r.value),r.attempts.toString(),r.operator,r.rat,r.scenario,r.dataOrigin,r.source,r.band,r.pci,r.rsrp,r.sinr)}
        val network=mutableListOf(listOf("Device Role","Metric","Operator","RAT","Band","PCI","RSRP","SINR","Interpretation"))
        rows.forEach{r->network+=listOf(r.role,metric(r.metricId),r.operator,r.rat,r.band.ifBlank{"N/A"},r.pci.ifBlank{"N/A"},r.rsrp.ifBlank{"N/A"},r.sinr.ifBlank{"N/A"},
            "Source observation; do not claim root cause from summary network metrics")}
        decisions.filter{it.evidenceIds.isNotEmpty()}.forEach{d->network+=listOf(d.dutRole,metric(d.metricId),"","","","","","",ExperienceNetworkHintsV130.describe(d,rows))}
        val abnormal=mutableListOf(listOf("Metric","Device","Automatic Status","Final Status","Gap","Rule","Evidence","Assessment"))
        decisions.filter{it.automated==ExperienceVerdict.FAIL||it.automated==ExperienceVerdict.OBSERVE}.forEach{d->abnormal+=listOf(metric(d.metricId),d.dutRole,d.automated.name,d.finalVerdict.name,fmt(d.difference),d.ruleSnapshot?.toString().orEmpty(),d.evidenceIds.joinToString(";"),d.reason)}
        val issues=mutableListOf(listOf("Metric","DUT","AUTO","FINAL","Override Reason","Bug ID","Follow-up"))
        decisions.filter{it.finalVerdict==ExperienceVerdict.FAIL||it.finalVerdict==ExperienceVerdict.OBSERVE}.forEach{d->issues+=listOf(metric(d.metricId),d.dutRole,d.automated.name,d.finalVerdict.name,d.overrideReason,d.bugId,"Review actual network traces and submit/track Bug where evidence supports disadvantage")}
        val source=mutableListOf(listOf("Report ID","Session","Role","Metric","Original Source","Origin","Timestamp","Attempts"))
        rows.forEach{r->source+=listOf(r.id,r.session,r.role,r.metricId,r.source,r.dataOrigin,r.recordedAt.toString(),r.attempts.toString())}
        val rulesSheet=mutableListOf(listOf("Rule Version",ruleVersion),listOf("Metric","Active","Delta Unit","Observe At","Fail At","Min Attempts"))
        rules.forEach{r->rulesSheet+=listOf(metric(r.metricId),r.enabled.toString(),ExperienceMetrics.find(r.metricId)?.deltaUnit?.name.orEmpty(),fmt(r.observeAt),fmt(r.failAt),r.minAttempts.toString())}
        val sheets=listOf("Summary" to summary,"Test Results" to testResults,"DUT vs REF" to compare,"Test Details" to details,
            "Network Analysis" to network,"Abnormal Events" to abnormal,"Issues" to issues,"Raw Data Index" to source,"Rule Settings" to rulesSheet)
        val safe=session.replace(Regex("[^A-Za-z0-9_-]"),"_").take(35)
        return save(context,"Communication_Experience_Test_Result_${safe}_$date.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",PingExporter.simpleXlsx(sheets),date)
    }
    private fun table(headers:List<String>,rows:List<List<String>>):String="<table><thead><tr>"+
        headers.joinToString(""){"<th>${html(it)}</th>"}+"</tr></thead><tbody>"+
        rows.joinToString(""){r->"<tr>"+r.joinToString(""){"<td>${html(it)}</td>"}+"</tr>"}+"</tbody></table>"
    private fun style():String="""<style>body{font-family:Arial,'Microsoft YaHei',sans-serif;line-height:1.52;color:#25364d;margin:22px auto;max-width:1060px;padding:0 24px}h1{color:#1c5da9}h2{border-left:4px solid #2674d6;padding-left:10px;margin-top:28px}table{border-collapse:collapse;width:100%;font-size:12px}th{background:#eaf2fd;color:#1f4b83}td,th{border:1px solid #d8e2f1;padding:8px;vertical-align:top}p{white-space:pre-wrap}.muted{color:#68768a}.tag{padding:2px 7px;border-radius:5px;background:#ecf3fb}@media print{body{padding:0;margin:0}h2{break-after:avoid}}</style>"""
    fun dailyHtml(session:String,date:String,rows:List<ExperienceObservation>,decisions:List<ExperienceDecision>,notes:Map<String,String>,ruleVersion:String):String {
        val filtered=rows.filter{it.session==session};val visible=decisions.filter{it.dutValue!=null||it.refValue!=null}
        val cnt=summaryCounts(visible)
        val op=filtered.map{it.operator}.distinct().joinToString(", ")
        val sc=filtered.map{it.scenario}.distinct().joinToString(", ")
        val overview=listOf(listOf("Date / Time Window",date),listOf("Test Scenario / Site",sc),listOf("Operator / SIM Setup",op),
            listOf("Planned / Actual RAT",filtered.map{it.rat}.distinct().joinToString(", ")),
            listOf("DUT 1 / DUT 2",filtered.filter{it.role.startsWith("DUT")}.map{it.role}.distinct().joinToString(", ")),
            listOf("REF 1 / REF 2",filtered.filter{it.role.startsWith("REF")}.map{it.role}.distinct().joinToString(", ")),
            listOf("Session / Report ID",session),listOf("Evaluation Rule",ruleVersion))
        val metrics=visible.map{d->listOf(metric(d.metricId),d.dutRole,fmt(d.dutValue),fmt(d.refValue),d.finalVerdict.name,htmlSafeText(d.reason))}
        val findings=visible.filter{it.finalVerdict!=ExperienceVerdict.PASS}.map{d->listOf(d.finalVerdict.name,metric(d.metricId)+" – "+d.dutRole,
            d.reason+"; "+ExperienceNetworkHintsV130.describe(d,filtered))}
        val issues=visible.filter{it.bugId.isNotBlank()||it.finalVerdict==ExperienceVerdict.FAIL}.map{d->listOf(d.bugId.ifBlank{"Pending"},metric(d.metricId),d.finalVerdict.name,d.overrideReason)}
        return "<!doctype html><html><head><meta charset='utf-8'>${style()}</head><body>"+
            "<h1>Communication Experience Daily Report — $date</h1><p class='muted'>Source-backed draft; ${html(ruleVersion)}; Session ${html(session)}</p>"+
            "<h2>1. Executive Summary</h2><p>${html(notes["summary"].orEmpty().ifBlank{"${visible.size} DUT comparisons were evaluated. Findings require engineer confirmation; unobserved items are not reported as PASS."})}</p>"+
            table(listOf("Tests evaluated","PASS","FAIL","OBSERVE","N/A"),listOf(listOf(visible.size.toString(),"${cnt[ExperienceVerdict.PASS]}","${cnt[ExperienceVerdict.FAIL]}","${cnt[ExperienceVerdict.OBSERVE]}","${cnt[ExperienceVerdict.NA]}")))+
            "<h2>2. Test Overview</h2>"+table(listOf("Field","Details"),overview)+
            "<h2>3. Consolidated DUT vs REF Results</h2>"+table(listOf("Metric","DUT","DUT value","Comparable REF mean","Result","Evidence"),metrics)+
            "<h2>4. Key Findings and Preliminary Analysis</h2>"+table(listOf("Finding / Status","Observed difference","Direction / Limitation"),findings)+
            "<h2>5. Issues and Bug Tracking</h2>"+table(listOf("Bug ID","Issue","Status","Notes"),issues)+
            "<h2>6. Actions for Next Test Day</h2><p>${html(notes["actions"].orEmpty().ifBlank{"Review marked findings with raw reports; collect modem logs where needed; repeat tests under comparable conditions."})}</p>"+
            "<h2>7. Supporting Evidence / Archiving</h2>"+table(listOf("Origin","Report reference"),filtered.map{listOf(it.dataOrigin,it.source)}.distinct())+
            "<h2>8. Ready-to-send Group Update</h2><p>${html(notes["group"].orEmpty().ifBlank{"$date · $sc · $op: ${visible.size} DUT metrics compared; ${cnt[ExperienceVerdict.FAIL]} FAIL, ${cnt[ExperienceVerdict.OBSERVE]} OBSERVE. Follow-up pending engineering review."})}</p>"+
            "<p class='muted'>Automatic verdicts reflect user-defined thresholds, not confirmed modem/RF root causes. N/A means missing or non-comparable data.</p></body></html>"
    }
    private fun htmlSafeText(v:String)=v
    fun exportDaily(context:Context,session:String,date:String,rows:List<ExperienceObservation>,decisions:List<ExperienceDecision>,notes:Map<String,String>,ruleVersion:String):Uri {
        val content=dailyHtml(session,date,rows,decisions,notes,ruleVersion)
        return save(context,"Communication_Experience_Daily_${date}_${session.take(20)}.html","text/html",content.toByteArray(),date)
    }
    fun weeklyHtml(endDate:String,all:List<ExperienceObservation>,rules:List<ExperienceRule>,notes:Map<String,String>,ruleVersion:String):String {
        val end=LocalDate.parse(endDate);val start=end.minusDays(6)
        val weekly=all.filter{runCatching{LocalDate.parse(it.date)}.getOrNull()?.let{d->!d.isBefore(start)&&!d.isAfter(end)}==true}
        val sessions=weekly.map{it.session}.distinct()
        val comparisons=sessions.flatMap{s->ExperienceAnalyzer.compare(weekly,rules,s)}.filter{it.dutValue!=null||it.refValue!=null}
        val cnt=summaryCounts(comparisons)
        val results=comparisons.map{listOf(it.metricId,it.dutRole,fmt(it.dutValue),fmt(it.refValue),it.finalVerdict.name,it.reason)}
        val finding=comparisons.filter{it.finalVerdict==ExperienceVerdict.FAIL||it.finalVerdict==ExperienceVerdict.OBSERVE}
        return "<!doctype html><html><head><meta charset='utf-8'>${style()}</head><body>"+
            "<h1>PK Communication Experience Weekly Report · ${html(start.toString())} – ${html(endDate)}</h1><p class='muted'>Rule version: ${html(ruleVersion)} · ${sessions.size} sessions · Source files retained</p>"+
            "<h2>一、问题/机会点收集与发现</h2><p>${html(notes["weekly_findings"].orEmpty().ifBlank{"${finding.size} observations or failures found; review detailed evidence before external escalation."})}</p>"+
            table(listOf("PASS","FAIL","OBSERVE","N/A"),listOf(listOf("${cnt[ExperienceVerdict.PASS]}","${cnt[ExperienceVerdict.FAIL]}","${cnt[ExperienceVerdict.OBSERVE]}","${cnt[ExperienceVerdict.NA]}")))+
            "<h2>二、复测还原与竞品对标｜开发与测试进展</h2>"+table(listOf("Metric","DUT","DUT","REF mean","Result","Evidence"),results)+
            "<h2>三、高优先级问题推进及风险</h2><p>${html(notes["weekly_risks"].orEmpty().ifBlank{"Bug follow-up is maintained by test owner. No root cause should be confirmed without modem/log evidence."})}</p>"+
            "<h2>四、组织能力沉淀</h2><p>${html(notes["weekly_knowledge"].orEmpty())}</p>"+
            "<h2>五、下期计划</h2><p>${html(notes["weekly_plan"].orEmpty())}</p>"+
            "<p class='muted'>Only selected/imported records are included. Missing Agent reports are not presumed successful.</p></body></html>"
    }
    fun exportWeekly(context:Context,endDate:String,all:List<ExperienceObservation>,rules:List<ExperienceRule>,notes:Map<String,String>,ruleVersion:String):Uri =
        save(context,"PK_Communication_Experience_Weekly_$endDate.html","text/html",weeklyHtml(endDate,all,rules,notes,ruleVersion).toByteArray(),endDate)
}
