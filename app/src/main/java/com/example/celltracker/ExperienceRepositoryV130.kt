package com.example.celltracker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Local-only persistence. Raw test files stay unchanged. */
class ExperienceRepositoryV130(private val context: Context) {
    private val prefs = context.getSharedPreferences("experience_workspace_v130", Context.MODE_PRIVATE)
    fun rules(): List<ExperienceRule> {
        val stored = runCatching { JSONArray(prefs.getString("rules", "[]")) }.getOrNull() ?: JSONArray()
        val saved = (0 until stored.length()).mapNotNull { i -> runCatching {
            val o = stored.getJSONObject(i)
            ExperienceRule(o.getString("metric"), o.optBoolean("enabled", false), o.optDouble("observe",5.0),
                o.optDouble("fail",10.0),o.optInt("min",1))
        }.getOrNull() }.associateBy { it.metricId }
        return ExperienceMetrics.all.map { saved[it.id] ?: ExperienceRule(it.id, false,
            if(it.deltaUnit==ExperienceDeltaUnit.ABSOLUTE) 1.0 else 5.0,
            if(it.deltaUnit==ExperienceDeltaUnit.ABSOLUTE) 2.0 else 10.0,1) }
    }
    fun saveRules(items: List<ExperienceRule>) {
        val arr=JSONArray(); items.forEach { r -> arr.put(JSONObject().apply {
            put("metric",r.metricId);put("enabled",r.enabled);put("observe",r.observeAt)
            put("fail",r.failAt);put("min",r.minAttempts)
        }) }; prefs.edit().putString("rules",arr.toString()).putLong("rule_revision",System.currentTimeMillis()).apply()
    }
    fun ruleVersion() = "v130-${prefs.getLong("rule_revision",0L)}"
    fun rows(): List<ExperienceObservation> {
        val a=runCatching { JSONArray(prefs.getString("measurements","[]")) }.getOrNull() ?: JSONArray()
        return (0 until a.length()).mapNotNull { i -> runCatching { val o=a.getJSONObject(i)
            ExperienceObservation(o.getString("id"),o.optString("session"),o.optString("date"),o.optString("scenario"),
                o.optString("operator"),o.optString("rat"),o.optString("role"),o.optString("metric"),o.getDouble("value"),
                o.optInt("attempts",0),o.optString("source"),o.optString("origin"),o.optLong("time"),
                o.optString("band"),o.optString("pci"),o.optString("rsrp"),o.optString("sinr"),
                o.optString("deviceModel"),o.optString("deviceId"),o.optString("runId"))
        }.getOrNull() }
    }
    fun saveRows(rows: List<ExperienceObservation>) {
        require(rows.size <= 2000) { "Workspace limit reached (2000 measurements). Export/archive earlier sessions before receiving more; nothing has been dropped." }
        val a=JSONArray(); rows.forEach { r -> a.put(JSONObject().apply {
            put("id",r.id);put("session",r.session);put("date",r.date);put("scenario",r.scenario)
            put("operator",r.operator);put("rat",r.rat);put("role",r.role);put("metric",r.metricId)
            put("value",r.value);put("attempts",r.attempts);put("source",r.source);put("origin",r.dataOrigin)
            put("time",r.recordedAt);put("band",r.band);put("pci",r.pci);put("rsrp",r.rsrp);put("sinr",r.sinr)
            put("deviceModel",r.deviceModel);put("deviceId",r.deviceId);put("runId",r.runId)
        }) }
        check(prefs.edit().putString("measurements",a.toString()).commit()) { "Could not durably save measurement data" }
    }
    fun addRows(new: List<ExperienceObservation>): Int {
        val rows=rows().associateByTo(linkedMapOf()) { it.id }
        val before=rows.size
        new.forEach { if(it.value.isFinite() && ExperienceMetrics.find(it.metricId)!=null) rows[it.id]=it }
        saveRows(rows.values.toList()); return rows.size-before
    }
    fun remove(id: String) = saveRows(rows().filterNot { it.id == id })
    fun addManual(session:String,date:String,scenario:String,operator:String,rat:String,role:String,metric:String,value:Double,attempts:Int):Int {
        return addRows(listOf(ExperienceObservation(UUID.randomUUID().toString(),session,date,scenario,operator,rat,role,metric,
            value,attempts,"Manual field entry","MANUAL",System.currentTimeMillis(),
            deviceModel="${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
            deviceId=ExperienceLanV130.deviceId(context),runId="MANUAL")))
    }
    fun overrides():Map<String,Triple<ExperienceVerdict,String,String>> {
        val json=runCatching{JSONObject(prefs.getString("overrides","{}"))}.getOrDefault(JSONObject())
        return json.keys().asSequence().mapNotNull { k -> runCatching { val o=json.getJSONObject(k)
            k to Triple(ExperienceVerdict.valueOf(o.getString("status")),o.optString("reason"),o.optString("bug"))
        }.getOrNull() }.toMap()
    }
    fun saveOverride(key:String, verdict:ExperienceVerdict, reason:String, bug:String) {
        require(reason.isNotBlank()) { "Review reason required" }
        val json=JSONObject(prefs.getString("overrides","{}"));json.put(key,JSONObject().apply {
            put("status",verdict.name);put("reason",reason);put("bug",bug)
        });prefs.edit().putString("overrides",json.toString()).apply()
    }
    fun note(name:String):String=prefs.getString("note_$name", "").orEmpty()
    fun saveNote(name:String,value:String){prefs.edit().putString("note_$name",value).apply()}

    /** Extract real local summaries, each linked back to unmodified original report file. */
    fun scanLocal(session:String,date:String,scenario:String,operator:String,declaredRat:String,role:String):Int {
        val results=mutableListOf<ExperienceObservation>()
        fun fresh(time:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(time))==date
        fun push(source:String,time:Long,metric:String,value:Double?,attempts:Int,op:String,rat:String,
                 band:String="",pci:String="",rsrp:String="",sinr:String="",origin:String="AUTO_LOCAL") {
            if(!fresh(time)||value==null||!value.isFinite()||attempts<1) return
            results+=ExperienceObservation("$session|$role|$metric|${source.hashCode()}",session,date,scenario,
                op.ifBlank { operator },rat.ifBlank { declaredRat },role,metric,value,attempts,source,origin,time,
                band,pci,rsrp,sinr,
                deviceModel="${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
                deviceId=ExperienceLanV130.deviceId(context),runId=source)
        }
        runCatching { PingRepository(context).loadHistory() }.getOrDefault(emptyList()).filter { fresh(it.startedAt) }.forEach { p ->
            val samples=runCatching{PingRepository(context).loadDetail(p.path).samples}.getOrDefault(emptyList())
            val s=samples.firstOrNull()?.snapshot
            push(p.path,p.startedAt,"PING_AVG_MS",p.averageLatencyMs,p.packetCount,s?.operator.orEmpty(),s?.displayRat.orEmpty(),
                s?.band.orEmpty(),s?.pci.orEmpty(),s?.rsrp.orEmpty(),s?.sinr.orEmpty())
            push(p.path,p.startedAt,"PING_SUCCESS",p.successRate,p.packetCount,s?.operator.orEmpty(),s?.displayRat.orEmpty(),
                s?.band.orEmpty(),s?.pci.orEmpty(),s?.rsrp.orEmpty(),s?.sinr.orEmpty())
        }
        runCatching{VideoLoadingRepository(context).history()}.getOrDefault(emptyList()).filter{fresh(it.startedAt)}.forEach{r->
            val good=r.samples.mapNotNull{it.delayMs?.toDouble()};val s=r.samples.firstOrNull()?.snapshot
            push(r.path,r.startedAt,"YOUTUBE_AVG_MS",good.takeIf{it.isNotEmpty()}?.average(),r.samples.size,
                s?.operator.orEmpty(),s?.displayRat.orEmpty(),s?.band.orEmpty(),s?.pci.orEmpty(),s?.rsrp.orEmpty(),s?.sinr.orEmpty())
        }
        runCatching{WhatsAppSendRepository(context).history()}.getOrDefault(emptyList()).filter{fresh(it.startedAt)}.forEach{r->
            val good=r.samples.filter{it.delayMs>=0};val s=r.samples.firstOrNull()?.snapshot
            push(r.path,r.startedAt,"WHATSAPP_SEND_AVG_MS",good.takeIf{it.isNotEmpty()}?.map{it.delayMs.toDouble()}?.average(),
                r.samples.size,s?.operator.orEmpty(),s?.displayRat.orEmpty(),s?.band.orEmpty(),s?.pci.orEmpty(),s?.rsrp.orEmpty(),s?.sinr.orEmpty())
        }
        // Call test can span two endpoints. Only use this local summary when identity is assigned by tester.
        runCatching{CallSetupRepository(context).loadHistory()}.getOrDefault(emptyList()).filter{fresh(it.startedAt)}.forEach{r->
            push(r.path,r.startedAt,"CALL_SUCCESS",r.successRate,r.attempts,operator,declaredRat,origin="AUTO_LOCAL_RAT_DECLARED")
            push(r.path,r.startedAt,"CALL_SETUP_AVG_MS",r.averageMs,r.attempts,operator,declaredRat,origin="AUTO_LOCAL_RAT_DECLARED")
        }
        // TikTok original CSV lives under MediaStore Downloads; never mistake exported summaries for test runs.
        runCatching {
            val collection=android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection=arrayOf(android.provider.MediaStore.Downloads._ID,
                android.provider.MediaStore.Downloads.DISPLAY_NAME,
                android.provider.MediaStore.Downloads.DATE_ADDED,
                android.provider.MediaStore.Downloads.RELATIVE_PATH)
            context.contentResolver.query(collection,projection,null,null,null)?.use { cursor ->
                val iId=cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                val iName=cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                val iDate=cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DATE_ADDED)
                val iPath=cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.RELATIVE_PATH)
                while(cursor.moveToNext()) {
                    val rel=cursor.getString(iPath).orEmpty()
                    val lag=rel.contains("TikTok Video Lag",true)
                    val upload=rel.contains("TikTok Upload",true)
                    if(!lag&&!upload)continue
                    val name=cursor.getString(iName).orEmpty().lowercase(Locale.US)
                    if(!name.endsWith(".csv")||name.endsWith("_events.csv")||name.endsWith("_cell_info.csv"))continue
                    val whenMs=cursor.getLong(iDate)*1000L
                    if(!fresh(whenMs))continue
                    val uri=android.content.ContentUris.withAppendedId(collection,cursor.getLong(iId))
                    val raw=context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:continue
                    val parsed=runCatching{TikTokReportExporter.parse(raw)}.getOrNull()?:continue
                    val f=parsed.fields
                    val op=f["Operator"].orEmpty()
                    val attempts=(f["Videos"]?.toIntOrNull()?:f["Completed"]?.toIntOrNull()?:parsed.events.size).coerceAtLeast(1)
                    if(lag) {
                        push(uri.toString(),whenMs,"TIKTOK_STALLS",f["Lag Count"]?.toDoubleOrNull(),attempts,op,declaredRat,origin="AUTO_LOCAL_RAT_DECLARED")
                        push(uri.toString(),whenMs,"TIKTOK_STALL_SEC",f["Total Lag ms"]?.toDoubleOrNull()?.div(1000.0),attempts,op,declaredRat,origin="AUTO_LOCAL_RAT_DECLARED")
                    } else {
                        push(uri.toString(),whenMs,"TIKTOK_UPLOAD_AVG_MS",f["Average Upload ms"]?.toDoubleOrNull(),attempts,op,declaredRat,origin="AUTO_LOCAL_RAT_DECLARED")
                        push(uri.toString(),whenMs,"TIKTOK_UPLOAD_MAX_MS",f["Slowest Upload ms"]?.toDoubleOrNull(),attempts,op,declaredRat,origin="AUTO_LOCAL_RAT_DECLARED")
                    }
                }
            }
        }
        return addRows(results)
    }

    /** Supports the existing Communication Experience Test Results workbook (sheet3) and its CSV export. */
    fun importTemplate(uri:Uri,session:String,date:String,scenario:String,operator:String,rat:String):Pair<Int,String> {
        val mime=context.contentResolver.getType(uri).orEmpty()
        val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
            while(true) {val n=input.read(buf);if(n<0)break
                out.write(buf,0,n);if(out.size()>8_000_000) error("Import exceeds 8 MB")}
            out.toByteArray()
        } ?: error("Cannot read selected file")
        val tables=if(mime.contains("spreadsheet")||bytes.take(2).toByteArray().contentEquals(byteArrayOf(0x50,0x4B))) xlsxRows(bytes)
            else parseCsv(bytes.toString(Charsets.UTF_8))
        val header=tables.firstOrNull().orEmpty().map { it.trim().removePrefix("\uFEFF") }
        fun col(name:String)=header.indexOfFirst{it.equals(name,true)}
        fun get(row:List<String>,name:String)=row.getOrNull(col(name)).orEmpty().trim()
        require(col("Test Case ID")>=0 && col("Role (DUT/REF)")>=0) { "Select the Test Results sheet/template CSV" }
        val incoming=mutableListOf<ExperienceObservation>();var skipped=0
        tables.drop(1).forEachIndexed { i,row ->
            val role=get(row,"Role (DUT/REF)").uppercase(Locale.US).replace(" ","")
            val actualRole=role.takeIf{it in ExperienceAnalyzer.roles}
            if(get(row,"Test Case ID").isBlank())return@forEachIndexed
            if(actualRole==null){skipped++;return@forEachIndexed}
            val test=get(row,"Test Case ID").uppercase(Locale.US)
            val attempts=get(row,"Attempts").toIntOrNull()?:0
            val success=get(row,"Successes").toIntOrNull()
            val rawDate=get(row,"Date")
            val measuredDate=rawDate.toDoubleOrNull()?.takeIf { it in 40000.0..80000.0 }?.let {
                java.time.LocalDate.of(1899,12,30).plusDays(it.toLong()).toString()
            } ?: rawDate.ifBlank { date }
            val sc=get(row,"Scenario").ifBlank{scenario}
            val op=get(row,"Operator").ifBlank{operator}
            val r=get(row,"Actual RAT").ifBlank{rat}
            val source="Imported Test Results row ${i+2} / ${uri.lastPathSegment.orEmpty()}"
            fun add(metric:String,v:Double?){if(v!=null && v.isFinite())incoming+=ExperienceObservation(
                "$session|$actualRole|$metric|$source",session,measuredDate,sc,op,r,actualRole,metric,
                v,attempts,source,"IMPORTED_TEMPLATE",System.currentTimeMillis()+i,
                rsrp=get(row,"RSRP (dBm)"),deviceModel=get(row,"Device"),
                deviceId=get(row,"Device ID"),runId=get(row,"Report File / Link").ifBlank{"imported_row_${i+2}"})}
            when (test) {
                "SIG-01" -> {
                    add("MFT_PING_AVG_MS",get(row,"Mean Latency (ms)").toDoubleOrNull())
                    add("MFT_PING_SUCCESS", if(success!=null&&attempts>0)100.0*success/attempts else null)
                }
                "DL-01" -> add("MFT_SHORT_VIDEO_AVG_MS",get(row,"Mean Duration (s)").toDoubleOrNull()?.times(1000))
                "DL-02" -> {
                    add("TIKTOK_STALLS",get(row,"Stall Count").toDoubleOrNull())
                    add("TIKTOK_STALL_SEC",get(row,"Total Stall (s)").toDoubleOrNull())
                }
                "DL-03" -> add("YOUTUBE_AVG_MS",get(row,"Mean Duration (s)").toDoubleOrNull()?.times(1000))
                "UL-01" -> {
                    add("TIKTOK_UPLOAD_AVG_MS",get(row,"Mean Duration (s)").toDoubleOrNull()?.times(1000))
                    add("TIKTOK_UPLOAD_MAX_MS",get(row,"Max Duration (s)").toDoubleOrNull()?.times(1000))
                }
                "UL-02" -> add("WHATSAPP_SEND_AVG_MS",get(row,"Mean Duration (s)").toDoubleOrNull()?.times(1000))
                "CALL-01", "CALL-02", "CALL-03" -> {
                    val metric=when(test){"CALL-01"->"VOLTE_SUCCESS";"CALL-02"->"WHATSAPP_CALL_SUCCESS";else->"VOLTE_LONG_SUCCESS"}
                    if(success!=null&&attempts>0)add(metric,100.0*success/attempts)
                }
                "COV-01" -> add("RECOVERY_MS",get(row,"Mean Latency (ms)").toDoubleOrNull())
                else -> skipped++
            }
        }
        return addRows(incoming) to "${incoming.size} readings recognized, $skipped rows skipped (role must be DUT1/DUT2/REF1/REF2; explicit case names required)"
    }
    private fun parseCsv(csv:String):List<List<String>> {
        val rows=mutableListOf<List<String>>();var current=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var i=0
        while(i<csv.length){val c=csv[i];when {
            c=='"'&&quoted&&i+1<csv.length&&csv[i+1]=='"'->{cell.append('"');i++}
            c=='"'->quoted=!quoted
            c==','&&!quoted->{current.add(cell.toString());cell.setLength(0)}
            (c=='\n'||c=='\r')&&!quoted->{if(c=='\r'&&i+1<csv.length&&csv[i+1]=='\n')i++;
                current.add(cell.toString());cell.setLength(0);if(current.any{it.isNotBlank()})rows+=current.toList();current=mutableListOf()}
            else->cell.append(c)
        };i++};current.add(cell.toString());if(current.any{it.isNotBlank()})rows+=current;return rows
    }
    private fun xlsxRows(bytes:ByteArray):List<List<String>> {
        val entries=mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.inputStream()).use{zip->while(true){val e=zip.nextEntry?:break
            if(e.name=="xl/sharedStrings.xml"||e.name=="xl/worksheets/sheet3.xml"){
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                while(true){val n=zip.read(buf);if(n<0)break;out.write(buf,0,n)
                    if(out.size()>8_000_000)error("XLSX XML too large")}
                entries[e.name]=out.toByteArray()
            };zip.closeEntry()}}
        fun document(b:ByteArray)=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true
            runCatching{setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
            runCatching{setFeature("http://xml.org/sax/features/external-general-entities",false)}
        }.newDocumentBuilder().parse(b.inputStream())
        val shared=entries["xl/sharedStrings.xml"]?.let{b->val doc=document(b)
            val items=doc.getElementsByTagNameNS("*","si")
            (0 until items.length).map{items.item(it).textContent.orEmpty()}
        }.orEmpty()
        val sheet=entries["xl/worksheets/sheet3.xml"]?:error("Known Test Results sheet3 not found")
        val nodes=document(sheet).getElementsByTagNameNS("*","row")
        return (0 until nodes.length).map{idx-> val node=nodes.item(idx);val cells=node.childNodes
            val out=mutableMapOf<Int,String>()
            for(j in 0 until cells.length){val c=cells.item(j);if(c.localName!="c")continue
                val addr=c.attributes?.getNamedItem("r")?.nodeValue.orEmpty();val letters=addr.takeWhile{it.isLetter()}
                val column=letters.fold(0){acc,ch->acc*26+(ch.uppercaseChar()-'A'+1)}-1
                val type=c.attributes?.getNamedItem("t")?.nodeValue.orEmpty()
                val v=c.textContent.orEmpty();out[column]=if(type=="s")shared.getOrNull(v.toIntOrNull()?:-1).orEmpty() else v
            };(0..(out.keys.maxOrNull()?:-1)).map{out[it].orEmpty()}
        }.filter{it.isNotEmpty()}
    }
}
