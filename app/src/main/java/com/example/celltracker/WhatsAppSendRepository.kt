package com.example.celltracker

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

class WhatsAppSendRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("celltracker_whatsapp_send", Context.MODE_PRIVATE)

    fun loadConfig() = WhatsAppSendConfig(
        prefs.getBoolean("auto_record", true),
        TestMetadata(
            prefs.getString("meta_scenario", "Mobility") ?: "Mobility",
            prefs.getString("meta_operator", "Zong") ?: "Zong",
            prefs.getString("meta_rat", "5G") ?: "5G",
            prefs.getString("meta_task", "WhatsApp Image Send") ?: "WhatsApp Image Send",
            prefs.getString("meta_location", "") ?: ""
        )
    )

    fun saveConfig(c: WhatsAppSendConfig) {
        prefs.edit().putBoolean("auto_record", c.autoRecord)
            .putString("meta_scenario", c.metadata.scenario).putString("meta_operator", c.metadata.operator)
            .putString("meta_rat", c.metadata.rat).putString("meta_task", c.metadata.task).putString("meta_location", c.metadata.location)
            .apply()
    }
    fun arm(c: WhatsAppSendConfig) { saveConfig(c); prefs.edit().putBoolean("armed", true).apply() }
    fun disarm() { prefs.edit().putBoolean("armed", false).apply() }
    fun isArmed() = prefs.getBoolean("armed", false)

    fun create(start:Long):File {
        val cfg=loadConfig(); val prefix=cfg.metadata.displayName().ifBlank{"WhatsApp Image Send"}.replace(Regex("[^A-Za-z0-9 _.-]"),"_").trim().replace(Regex("\\s+"),"_")
        val file=File(dir(), "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date(start))}.csv")
        file.writeText(HEADER+"\n"); meta(file,start,0,"Running"); return file
    }
    fun append(file:File,s:WhatsAppSendSample){
        val n=s.snapshot
        val v=listOf(s.sequence,s.t0Ms,fmt(s.t0Ms),s.t1Ms,fmt(s.t1Ms),s.delayMs,s.t0ElapsedMs,s.t1ElapsedMs,s.t0Source,n.subscriptionId,n.simSlot+1,n.operator,n.displayRat,n.rsrp,n.rsrq,n.sinr,n.rssi,n.band,n.pci,n.arfcn,n.latitude?:"",n.longitude?:"")
        FileWriter(file,true).use{ it.appendLine(v.joinToString(","){x->csv(x.toString())}) }
    }
    fun finish(file:File,start:Long,end:Long,status:String){meta(file,start,end,status)}
    fun history():List<WhatsAppSendDetail> = dir().listFiles{f->f.extension=="csv"}?.mapNotNull{runCatching{load(it.absolutePath)}.getOrNull()}?.sortedByDescending{it.startedAt}.orEmpty()
    fun delete(path:String)=runCatching{File(path).delete()}.getOrDefault(false)
    fun load(path:String):WhatsAppSendDetail{
        val f=File(path); val rows=f.readLines().filter{it.isNotBlank()}; if(rows.isEmpty()) return WhatsAppSendDetail(path,f.lastModified(),0,"Empty",emptyList())
        val h=parse(rows.first()).withIndex().associate{it.value to it.index}; fun g(r:List<String>,k:String)=h[k]?.let{r.getOrNull(it)}.orEmpty()
        val samples=rows.drop(1).mapNotNull{raw-> val r=parse(raw); val seq=g(r,"sequence").toIntOrNull()?:return@mapNotNull null
            WhatsAppSendSample(seq,g(r,"t0_ms").toLongOrNull()?:0,g(r,"t1_ms").toLongOrNull()?:0,g(r,"delay_ms").toLongOrNull()?:0,
                PingNetworkSnapshot(subscriptionId=g(r,"subscription_id").toIntOrNull()?:-1,simSlot=(g(r,"sim_slot").toIntOrNull()?:1)-1,operator=g(r,"operator"),displayRat=g(r,"rat"),rsrp=g(r,"rsrp"),rsrq=g(r,"rsrq"),sinr=g(r,"sinr"),rssi=g(r,"rssi"),band=g(r,"band"),pci=g(r,"pci"),arfcn=g(r,"arfcn"),latitude=g(r,"latitude").toDoubleOrNull(),longitude=g(r,"longitude").toDoubleOrNull()),
                g(r,"t0_elapsed_ms").toLongOrNull()?:0,g(r,"t1_elapsed_ms").toLongOrNull()?:0,g(r,"t0_source"))
        }
        val p=Properties(); val mf=File(f.parentFile,f.nameWithoutExtension+".meta"); if(mf.exists()) mf.inputStream().use{p.load(it)}
        return WhatsAppSendDetail(path,p.getProperty("started")?.toLongOrNull()?.takeIf { it >= 946684800000L }
            ?: samples.firstOrNull()?.t0Ms?.takeIf { it >= 946684800000L }
            ?: f.lastModified(),p.getProperty("ended")?.toLongOrNull()?:0,p.getProperty("status","Completed"),samples)
    }
    private fun meta(f:File,s:Long,e:Long,status:String){Properties().apply{setProperty("started",s.toString());setProperty("ended",e.toString());setProperty("status",status)}.store(File(f.parentFile,f.nameWithoutExtension+".meta").outputStream(),"CellTracker WhatsApp Image Send")}
    private fun dir()=File(context.getExternalFilesDir(null),"whatsapp_send_results").apply{mkdirs()}
    private fun fmt(t:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date(t))
    private fun csv(v:String)="\""+v.replace("\"","\"\"")+"\""
    private fun parse(s:String):List<String>{val out=mutableListOf<String>();val b=StringBuilder();var q=false;var i=0;while(i<s.length){val c=s[i];if(c=='\"'&&q&&i+1<s.length&&s[i+1]=='\"'){b.append('\"');i++}else if(c=='\"')q=!q else if(c==','&&!q){out+=b.toString();b.setLength(0)}else b.append(c);i++};out+=b.toString();return out}
    companion object{const val HEADER="sequence,t0_ms,t0_time,t1_ms,t1_time,delay_ms,t0_elapsed_ms,t1_elapsed_ms,t0_source,subscription_id,sim_slot,operator,rat,rsrp,rsrq,sinr,rssi,band,pci,arfcn,latitude,longitude"}
}
