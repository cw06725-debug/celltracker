package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TikTokReportExporter {
    data class Event(val seq:String,val item:String,val type:String,val t0:String,val t1:String,val durationMs:Long?)
    data class Parsed(val fields:LinkedHashMap<String,String>,val header:List<String>,val rows:List<List<String>>,val events:List<Event>,val isLag:Boolean)

    fun export(c:Context, uriString:String):ExportResult {
        val source=Uri.parse(uriString)
        val raw=c.contentResolver.openInputStream(source)?.bufferedReader()?.use{it.readText()}
            ?: error("Unable to read TikTok report")
        val p=parse(raw)
        val task=p.fields["Task"].orEmpty().ifBlank{if(p.isLag)"TikTok Video Lag" else "TikTok Upload"}
        val safe=task.replace(Regex("[\\/:*?\"<>|\\r\\n]+"),"_").replace(' ','_').take(48)
        val rec=File(p.fields["Recording Path"].orEmpty())
        val cellRows=if(rec.exists()) readCsv(rec) else emptyList()
        val recordingStarted=cellRows.drop(1).firstOrNull()?.firstOrNull()?.let(::parseDateTime) ?: 0L
        val started=recordingStarted.takeIf{it>0} ?: System.currentTimeMillis()
        val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date(started))
        val base="${safe}_$stamp"
        val cellHeader=cellRows.firstOrNull().orEmpty()
        val cellData=cellRows.drop(1)

        fun windowRows(e:Event):List<List<String>> {
            val a=parseClock(e.t0); val b=parseClock(e.t1)
            if(a<=0||b<=0||cellHeader.isEmpty()) return emptyList()
            val ti=cellHeader.indexOf("timestamp")
            return cellData.filter { row ->
                val x=row.getOrNull(ti)?.let(::parseFullTime) ?: 0L
                x in a..b
            }
        }

        val summary=mutableListOf<List<String>>()
        summary+=listOf("CellTracker ${if(p.isLag)"TikTok Video Lag" else "TikTok Upload"} Report")
        listOf("Task","Operator","Swipe Mode","Upload Type","Start","End","Duration ms","Videos","Lag Count","Total Lag ms","Average Lag ms","Completed","Recording Path").forEach{
            p.fields[it]?.let{v->summary+=listOf(it,v)}
        }
        summary+=listOf("Analysis",analysis(p,cellHeader,cellData))

        val eventSheet=mutableListOf<List<String>>()
        eventSheet+=if(p.isLag) listOf("Sequence","Video","Type","Lag Start (T0)","Lag End (T1)","Duration ms")
                    else listOf("Sequence","Type","Post Touch (T0)","Posted (T1)","Upload Duration ms","Result")
        eventSheet+=p.rows

        val sheets=mutableListOf<Pair<String,List<List<String>>>>()
        sheets+="Summary" to summary
        sheets+=(if(p.isLag)"Lag Events" else "Upload Attempts") to eventSheet
        if(cellHeader.isNotEmpty()) sheets+="Cell Info" to (listOf(cellHeader)+cellData)
        p.events.forEachIndexed { i,e ->
            val rows=windowRows(e)
            val meta=listOf(
                listOf(if(p.isLag)"Lag #${i+1}" else "Upload #${i+1}"),
                listOf("T0",e.t0), listOf("T1",e.t1),
                listOf("Duration ms",e.durationMs?.toString().orEmpty()),
                listOf("Cell samples in T0-T1",rows.size.toString()),
                emptyList()
            )
            sheets+=(if(p.isLag)"Lag ${i+1}" else "Upload ${i+1}") to (meta + if(cellHeader.isNotEmpty()) listOf(cellHeader)+rows else listOf(listOf("Cell Info","Unavailable")))
        }

        val htmlName="${base}_summary.html"
        val htmlUri=save(c,htmlName,"text/html",html(p,cellHeader,cellData).toByteArray(),started).toString()
        val xlsxName="${base}_report.xlsx"
        val xlsxUri=save(c,xlsxName,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",PingExporter.simpleXlsx(sheets),started).toString()
        val cellName="${base}_cell_info.csv"
        val cellUri=save(c,cellName,"text/csv",(if(rec.exists())rec.readBytes() else "Cell Info unavailable\n".toByteArray()),started).toString()
        val kmlName="${base}_track.kml"
        val kmlUri=save(c,kmlName,"application/vnd.google-earth.kml+xml",kml(cellHeader,cellData,p.events).toByteArray(),started).toString()
        val rawName="${base}_events.csv"
        val rawUri=save(c,rawName,"text/csv",raw.toByteArray(),started).toString()
        return ExportResult(
            message="Export successful · HTML Summary + Excel + Cell Info + Track KML",
            exportedFileUris=listOf(rawUri,cellUri),
            summaryUri=htmlUri,summaryName=htmlName,
            excelUri=xlsxUri,excelName=xlsxName,
            kmlUri=kmlUri,kmlName=kmlName
        )
    }

    fun parse(raw:String):Parsed{
        val lines=raw.lines()
        val fields=linkedMapOf<String,String>()
        var hi=-1
        lines.forEachIndexed{i,line->
            if(line.startsWith("Sequence,")) hi=i
            else if(hi<0&&line.contains(',')){
                val x=parseCsv(line); if(x.isNotEmpty()) fields[x[0]]=x.drop(1).joinToString(",")
            }
        }
        val header=if(hi>=0)parseCsv(lines[hi]) else emptyList()
        val rows=if(hi>=0)lines.drop(hi+1).filter{it.isNotBlank()}.map(::parseCsv) else emptyList()
        val lag=header.any{it.contains("Lag Start")}
        val events=rows.mapNotNull{r->
            if(lag){
                val d=r.getOrNull(5)?.toLongOrNull()
                if(r.size>=6&&r.getOrNull(3).orEmpty().isNotBlank()&&r.getOrNull(4).orEmpty().isNotBlank())
                    Event(r[0],r[1],r[2],r[3],r[4],d) else null
            }else{
                val d=r.getOrNull(4)?.toLongOrNull()
                if(r.size>=5&&r.getOrNull(2).orEmpty().isNotBlank()&&r.getOrNull(3).orEmpty().isNotBlank())
                    Event(r[0],r[1],"UPLOAD",r[2],r[3],d) else null
            }
        }
        return Parsed(fields,header,rows,events,lag)
    }

    private fun analysis(p:Parsed,h:List<String>,rows:List<List<String>>):String{
        if(p.events.isEmpty()) return if(p.isLag)"No perceived lag event was recorded." else "No completed upload attempt is available."
        if(h.isEmpty()) return "Cell Info was not available for this session; event timing is available but radio correlation cannot be concluded."
        val ti=h.indexOf("timestamp"); val rat=h.indexOf("display_rat"); val pci=h.indexOf("pci"); val rsrp=h.indexOf("rsrp"); val sinr=h.indexOf("sinr")
        var weak=0;var cellChange=0;var ratChange=0
        p.events.forEach{e->
            val a=parseClock(e.t0);val b=parseClock(e.t1)
            val w=rows.filter{r->(r.getOrNull(ti)?.let(::parseFullTime)?:0L) in a..b}
            val rv=w.mapNotNull{it.getOrNull(rsrp)?.filter{ch->ch=='-'||ch.isDigit()}?.toIntOrNull()}
            val sv=w.mapNotNull{it.getOrNull(sinr)?.filter{ch->ch=='-'||ch.isDigit()}?.toIntOrNull()}
            if(rv.any{it<=-110}||sv.any{it<0}) weak++
            if(w.mapNotNull{it.getOrNull(pci)}.filter{it.isNotBlank()}.distinct().size>1) cellChange++
            if(w.mapNotNull{it.getOrNull(rat)}.filter{it.isNotBlank()}.distinct().size>1) ratChange++
        }
        val parts=mutableListOf<String>()
        if(weak>0) parts+="$weak event(s) overlapped weak RSRP or negative SINR"
        if(cellChange>0) parts+="$cellChange event(s) overlapped serving-cell PCI change"
        if(ratChange>0) parts+="$ratChange event(s) overlapped RAT change"
        return if(parts.isEmpty()) "No obvious weak-signal, serving-cell or RAT transition was found inside the measured event windows. Compare with REF and transport/server behavior before assigning cause."
        else parts.joinToString("; ")+". These are correlations, not proof of root cause; compare the same timestamps with REF."
    }

    private fun html(p:Parsed,h:List<String>,cell:List<List<String>>):String=buildString{
        append("<html><head><meta name='viewport' content='width=device-width'><style>body{font-family:sans-serif;margin:18px}.card{border:1px solid #ddd;border-radius:12px;padding:12px;margin:10px 0}table{border-collapse:collapse;width:100%;display:block;overflow:auto}th,td{padding:7px;border-bottom:1px solid #ddd;white-space:nowrap}</style></head><body>")
        append("<h1>${if(p.isLag)"TikTok Video Lag" else "TikTok Upload"}</h1><div class='card'>")
        p.fields.filterKeys{it!="Recording Path"}.forEach{(k,v)->append("<b>${esc(k)}</b>: ${esc(v)}<br>")}
        append("</div><div class='card'><b>Analysis</b><br>${esc(analysis(p,h,cell))}</div>")
        append("<h2>${if(p.isLag)"Lag Events" else "Upload Attempts"}</h2><table><tr>")
        p.header.forEach{append("<th>${esc(it)}</th>")};append("</tr>")
        p.rows.forEach{r->append("<tr>");r.forEach{append("<td>${esc(it)}</td>")};append("</tr>")}
        append("</table></body></html>")
    }

    private fun kml(h:List<String>,rows:List<List<String>>,events:List<Event>):String{
        val lati=h.indexOf("latitude");val loni=h.indexOf("longitude");val ti=h.indexOf("timestamp")
        val valid=rows.mapNotNull{r->
            val lat=r.getOrNull(lati)?.toDoubleOrNull();val lon=r.getOrNull(loni)?.toDoubleOrNull()
            if(lat!=null&&lon!=null&&lat!=0.0&&lon!=0.0) Triple(r.getOrNull(ti).orEmpty(),lat,lon) else null
        }
        return buildString{
            append("<?xml version='1.0' encoding='UTF-8'?><kml xmlns='http://www.opengis.net/kml/2.2'><Document><name>TikTok Track</name>")
            if(valid.isNotEmpty()){append("<Placemark><name>Track</name><LineString><coordinates>");valid.forEach{append("${it.third},${it.second},0 ")};append("</coordinates></LineString></Placemark>")}
            events.forEachIndexed{i,e->
                val target=parseClock(e.t0)
                val near=valid.minByOrNull{kotlin.math.abs(parseFullTime(it.first)-target)}
                if(near!=null){append("<Placemark><name>${if(e.type=="UPLOAD")"Upload" else "Lag"} #${i+1} T0</name><description>${esc(e.t0)} - ${esc(e.t1)}</description><Point><coordinates>${near.third},${near.second},0</coordinates></Point></Placemark>")}
            }
            append("</Document></kml>")
        }
    }

    private fun readCsv(f:File)=f.readLines().filter{it.isNotBlank()}.map(::parseCsv)
    private fun parseCsv(s:String):List<String>{val o=mutableListOf<String>();val b=StringBuilder();var q=false;var i=0;while(i<s.length){val c=s[i];if(c=='"'&&q&&i+1<s.length&&s[i+1]=='"'){b.append('"');i++}else if(c=='"')q=!q else if(c==','&&!q){o+=b.toString();b.setLength(0)}else b.append(c);i++};o+=b.toString();return o}
    private fun parseClock(s:String):Long=runCatching{SimpleDateFormat("HH:mm:ss.SSS",Locale.US).parse(s)?.time?:0L}.getOrDefault(0L)
    private fun parseFullTime(s:String):Long=parseClock(s.substringAfter(' ',s))
    private fun parseDateTime(s:String):Long=runCatching{SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).parse(s)?.time?:0L}.getOrDefault(0L)
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
    private fun save(c:Context,name:String,mime:String,bytes:ByteArray,started:Long):Uri{
        if(Build.VERSION.SDK_INT<29) error("Android 10+ required")
        val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime);put(MediaStore.Downloads.RELATIVE_PATH,ReportStorage.relativePath("TikTok",started))}
        val u=c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Create export failed")
        c.contentResolver.openOutputStream(u)!!.use{it.write(bytes)};return u
    }
}
