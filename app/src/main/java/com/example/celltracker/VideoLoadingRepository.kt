package com.example.celltracker

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class VideoLoadingRepository(private val context:Context){
 private val prefs=context.getSharedPreferences("celltracker_video_loading",Context.MODE_PRIVATE)
 fun loadConfig()=VideoLoadingConfig(prefs.getInt("count",10),prefs.getLong("timeout",15000),prefs.getLong("return_wait",2000),prefs.getBoolean("auto_record",true))
 fun saveConfig(c:VideoLoadingConfig){prefs.edit().putInt("count",c.count).putLong("timeout",c.timeoutMs).putLong("return_wait",c.returnWaitMs).putBoolean("auto_record",c.autoRecord).apply()}
 fun arm(c:VideoLoadingConfig){saveConfig(c);prefs.edit().putBoolean("armed",true).apply()}
 fun isArmed()=prefs.getBoolean("armed",false)
 fun disarm(){prefs.edit().putBoolean("armed",false).apply()}
 fun create(start:Long):File{val f=File(dir(),"YouTube_Video_Loading_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date(start))}.csv");f.writeText(HEADER+"\n");meta(f,start,0,"Running",null);return f}
 fun append(f:File,s:VideoLoadingSample){val n=s.snapshot;val v=listOf(s.sequence,s.startMs,fmt(s.startMs),s.loadedMs.takeIf{it>0}?:"",s.delayMs?:"",s.result,s.detection,s.title,n.subscriptionId,n.simSlot+1,n.operator,n.displayRat,n.rsrp,n.rsrq,n.sinr,n.rssi,n.band,n.pci,n.arfcn,n.latitude?:"",n.longitude?:"");FileWriter(f,true).use{w->w.appendLine(v.joinToString(","){csv(it.toString())})}}
 fun finish(f:File,start:Long,end:Long,status:String,recording:String?){meta(f,start,end,status,recording)}
 fun history():List<VideoLoadingDetail> = dir().listFiles{f->f.extension=="csv"}?.mapNotNull{runCatching{load(it.absolutePath)}.getOrNull()}?.sortedByDescending{it.startedAt}.orEmpty()
 fun load(path:String):VideoLoadingDetail{val f=File(path);val rows=f.readLines().filter{it.isNotBlank()};val h=parse(rows.first());val ix=h.withIndex().associate{it.value to it.index};fun g(r:List<String>,k:String)=r.getOrNull(ix[k]?:-1).orEmpty();val ss=rows.drop(1).map{r0->val r=parse(r0);VideoLoadingSample(g(r,"sequence").toInt(),g(r,"title"),g(r,"start_ms").toLong(),g(r,"loaded_ms").toLongOrNull()?:0,g(r,"delay_ms").toLongOrNull(),g(r,"result"),g(r,"detection"),PingNetworkSnapshot(subscriptionId=g(r,"subscription_id").toIntOrNull()?:-1,simSlot=(g(r,"sim_slot").toIntOrNull()?:1)-1,operator=g(r,"operator"),displayRat=g(r,"rat"),rsrp=g(r,"rsrp"),rsrq=g(r,"rsrq"),sinr=g(r,"sinr"),rssi=g(r,"rssi"),band=g(r,"band"),pci=g(r,"pci"),arfcn=g(r,"arfcn"),latitude=g(r,"latitude").toDoubleOrNull(),longitude=g(r,"longitude").toDoubleOrNull()))};val p=Properties();val mf=File(f.parentFile,f.nameWithoutExtension+".meta");if(mf.exists())mf.inputStream().use{p.load(it)};return VideoLoadingDetail(path,p.getProperty("started")?.toLongOrNull()?:ss.firstOrNull()?.startMs?:f.lastModified(),p.getProperty("ended")?.toLongOrNull()?:ss.lastOrNull()?.loadedMs?:0,p.getProperty("status","Completed"),ss,p.getProperty("recording")?.takeIf{it.isNotBlank()})}
 private fun meta(f:File,s:Long,e:Long,status:String,r:String?){Properties().apply{setProperty("started",s.toString());setProperty("ended",e.toString());setProperty("status",status);setProperty("recording",r.orEmpty())}.store(File(f.parentFile,f.nameWithoutExtension+".meta").outputStream(),"CellTracker YouTube Video Loading")}
 private fun dir()=File(context.getExternalFilesDir(null),"video_loading_results").apply{mkdirs()}
 private fun fmt(t:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date(t))
 private fun csv(s:String)="\""+s.replace("\"","\"\"")+"\""
 private fun parse(s:String):List<String>{val o=mutableListOf<String>();val b=StringBuilder();var q=false;var i=0;while(i<s.length){val c=s[i];if(c=='\"'&&q&&i+1<s.length&&s[i+1]=='\"'){b.append('\"');i++}else if(c=='\"')q=!q else if(c==','&&!q){o+=b.toString();b.setLength(0)}else b.append(c);i++};o+=b.toString();return o}
 companion object{const val HEADER="sequence,start_ms,start_time,loaded_ms,delay_ms,result,detection,title,subscription_id,sim_slot,operator,rat,rsrp,rsrq,sinr,rssi,band,pci,arfcn,latitude,longitude"}
}
