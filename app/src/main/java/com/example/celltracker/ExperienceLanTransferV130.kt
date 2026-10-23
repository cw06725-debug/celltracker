package com.example.celltracker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Explicit, post-test, trusted-local-Wi-Fi transfer. Never used by Device Link or during a test. */
internal object ExperienceLanV130 {
    const val PORT = 48731
    private const val MAX_RAW = 12L * 1024 * 1024
    private const val MAX_BUNDLE = 55L * 1024 * 1024
    const val MAX_JSON = 4 * 1024 * 1024
    private val hexRegex = Regex("[0-9a-f]{64}")
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.US,it.toInt() and 255) }
    fun shaFile(f: File): String {
        val md=MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input -> val b=ByteArray(8192)
            while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)} }
        return md.digest().joinToString(""){ "%02x".format(Locale.US,it.toInt() and 255) }
    }
    fun safeAddress(address:String):String {
        val parts=address.trim().split('.')
        require(parts.size==4 && parts.all{it.isNotEmpty()&&it.length<=3&&it.all(Char::isDigit)}) { "Enter a numeric local IPv4 address" }
        val n=parts.map{it.toIntOrNull()?:-1}
        require(n.all{it in 0..255}) { "Invalid IPv4 address" }
        require(n[0]==10 || n[0]==192&&n[1]==168 || n[0]==172&&n[1] in 16..31) { "Only private LAN addresses allowed" }
        return n.joinToString(".")
    }
    fun localAddresses():List<String> = runCatching {
        val interfaces=NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces.filter{it.isUp&&!it.isLoopback &&
            listOf("wlan","swlan","ap","softap","eth").any { prefix -> it.name.startsWith(prefix,true) }}
            .sortedBy { if(it.name.startsWith("wlan")||it.name.startsWith("ap"))0 else 1 }
            .flatMap{it.inetAddresses.toList()}.filterIsInstance<Inet4Address>()
            .map{it.hostAddress.orEmpty()}.filter{runCatching{safeAddress(it)}.isSuccess}.distinct()
    }.getOrDefault(emptyList())
    fun deviceId(c:Context):String {
        val p=c.getSharedPreferences("experience_lan_v130",Context.MODE_PRIVATE)
        val old=p.getString("device_id",null)
        if(!old.isNullOrBlank())return old
        return UUID.randomUUID().toString().also{p.edit().putString("device_id",it).commit()}
    }
    fun randomCode():String {
        val alphabet="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";val rng=SecureRandom()
        return (1..12).map{alphabet[rng.nextInt(alphabet.length)]}.joinToString("")
    }
    private fun observationJson(r:ExperienceObservation)=JSONObject().apply {
        put("id",r.id);put("session",r.session);put("date",r.date);put("scenario",r.scenario)
        put("operator",r.operator);put("rat",r.rat);put("role",r.role);put("metric",r.metricId)
        put("value",r.value);put("attempts",r.attempts);put("source",r.source)
        put("origin",r.dataOrigin);put("time",r.recordedAt);put("band",r.band)
        put("pci",r.pci);put("rsrp",r.rsrp);put("sinr",r.sinr)
        put("deviceModel",r.deviceModel);put("deviceId",r.deviceId);put("runId",r.runId)
    }
    private fun reportIdFor(device:String,session:String,role:String,date:String,scenario:String,
                            operator:String,rat:String,rows:List<ExperienceObservation>,files:List<String>):String {
        val md=MessageDigest.getInstance("SHA-256")
        fun field(s:String){val b=s.toByteArray(Charsets.UTF_8)
            md.update(java.nio.ByteBuffer.allocate(4).putInt(b.size).array());md.update(b)}
        listOf("CTLAN1",device,session,role,date,scenario,operator,rat).forEach(::field)
        rows.forEach {r->listOf(r.id,r.session,r.date,r.scenario,r.operator,r.rat,r.role,
            r.metricId,r.value.toString(),r.attempts.toString(),r.source,r.dataOrigin,
            r.recordedAt.toString(),r.band,r.pci,r.rsrp,r.sinr).forEach(::field)}
        files.forEach(::field)
        return md.digest().joinToString(""){ "%02x".format(Locale.US,it.toInt() and 255) }
    }
    private fun decode(o:JSONObject)=ExperienceObservation(
        o.getString("id"),o.getString("session"),o.getString("date"),o.getString("scenario"),
        o.getString("operator"),o.getString("rat"),o.getString("role"),o.getString("metric"),
        o.getDouble("value"),o.getInt("attempts"),o.getString("source"),o.getString("origin"),
        o.getLong("time"),o.optString("band"),o.optString("pci"),o.optString("rsrp"),o.optString("sinr"),
        o.optString("deviceModel"),o.optString("deviceId"),o.optString("runId")
    )
    private fun shortName(s:String)=s.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"),"_").take(60).ifBlank{"report.csv"}
    private fun rawFile(c:Context, source:String):File? {
        // Only sources produced by this app are eligible. Never read arbitrary requested HTTP paths.
        if(source.startsWith("content://")) {
            val uri=Uri.parse(source)
            val tmp=File(c.cacheDir,"lan_raw_${UUID.randomUUID()}.tmp")
            try {
                c.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { out ->
                    val buf=ByteArray(8192);var size=0L
                    while(true){val n=input.read(buf);if(n<0)break;size+=n
                        if(size>MAX_RAW) error("Raw attachment exceeds limit");out.write(buf,0,n)}
                } } ?: error("Unreadable content URI")
                return tmp
            } catch (_:Exception) {tmp.delete();return null}
        }
        return runCatching { File(source).canonicalFile.takeIf { it.isFile && it.length() in 1..MAX_RAW } }.getOrNull()
    }
    data class Bundle(val zip:File, val id:String, val digest:String, val rowCount:Int, val files:Int, val skipped:Int)
    /** Freeze one self-contained snapshot. All original files are copied unchanged inside the ZIP. */
    fun prepare(c:Context,session:String,date:String,scenario:String,operator:String,rat:String,role:String):Bundle {
        require(session.isNotBlank()&&date.isNotBlank()&&role in ExperienceAnalyzer.roles)
        require(session.length<=128 && date.length<=40 && scenario.length<=160 && operator.length<=80 && rat.length<=40)
        val repo=ExperienceRepositoryV130(c)
        repo.scanLocal(session,date,scenario,operator,rat,role)
        val rows=repo.rows().filter { it.session==session && it.date==date && it.role==role &&
            !it.dataOrigin.startsWith("LAN_") && it.dataOrigin!="IMPORTED_LAN" }.sortedWith(compareBy({it.metricId},{it.id}))
        require(rows.isNotEmpty()){ "No local reports for selected date / role. Load test data first or adjust the date." }
        require(rows.size<=1500){"Too many readings for one transfer"}
        val attachments=mutableListOf<Triple<String,File,Boolean>>()
        var total=0L;var skipped=0
        for(source in rows.map{it.source}.distinct()) {
            val f=rawFile(c,source)
            if(f==null){skipped++;continue}
            if(total+f.length()>MAX_BUNDLE) {if(source.startsWith("content://"))f.delete();skipped++;continue}
            total+=f.length();attachments+=Triple(source,f,source.startsWith("content://"))
        }
        val device=deviceId(c)
        val checksums=attachments.map{shaFile(it.second)}
        val reportId=reportIdFor(device,session,role,date,scenario,operator,rat,rows,checksums)
        val manifest=JSONObject().apply {
            put("protocol",1);put("reportId",reportId);put("deviceId",device);put("model",Build.MODEL.orEmpty())
            put("session",session);put("date",date);put("scenario",scenario);put("operator",operator);put("rat",rat);put("role",role)
            put("created",System.currentTimeMillis())
            put("observations",JSONArray().apply { rows.forEach{put(observationJson(it))} })
            put("attachments",JSONArray().apply { attachments.forEachIndexed{i,a ->put(JSONObject().apply {
                put("source",a.first);put("entry","raw/${i.toString().padStart(3,'0')}_${shortName(a.first)}")
                put("size",a.second.length());put("sha256",checksums[i]) })} })
        }
        val json=manifest.toString().toByteArray(Charsets.UTF_8)
        require(json.size<=MAX_JSON){"Metadata too large"}
        val directory=File(c.cacheDir,"experience_lan").apply{mkdirs()}
        val zipFile=File(directory,"$reportId.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use {zip ->
                zip.putNextEntry(ZipEntry("manifest.json"));zip.write(json);zip.closeEntry()
                attachments.forEachIndexed {i,a ->
                    val entry=manifest.getJSONArray("attachments").getJSONObject(i).getString("entry")
                    zip.putNextEntry(ZipEntry(entry)); FileInputStream(a.second).use{it.copyTo(zip,8192)};zip.closeEntry()
                }
            }
            require(zipFile.length() in 1..(MAX_BUNDLE+MAX_JSON+2_000_000L)){"Bundle too large"}
        } finally {attachments.filter{it.third}.forEach{it.second.delete()}}
        return Bundle(zipFile,reportId,shaFile(zipFile),rows.size,attachments.size,skipped)
    }
    /** Import only after validating entire archive, identity, requested role and SHA-256 attachments. */
    fun receive(c:Context,repo:ExperienceRepositoryV130,zip:File,expectedSession:String,expectedRole:String):Pair<Int,String> {
        require(zip.length() in 1..(MAX_BUNDLE+MAX_JSON+2_000_000L)){"Invalid bundle size"}
        val destination=File(c.filesDir,"experience_lan_imports").apply{mkdirs()}
        val staging=File(destination,".pending_${UUID.randomUUID()}").apply{mkdirs()}
        try {
            var manifest:JSONObject?=null;var total=0L
            val encountered=mutableSetOf<String>()
            ZipInputStream(FileInputStream(zip)).use {input->
                while(true){val e=input.nextEntry?:break
                    require(!e.isDirectory&&encountered.add(e.name)){"Invalid or duplicate ZIP entry"}
                    require(e.name=="manifest.json" || Regex("raw/[0-9]{3}_[A-Za-z0-9._-]{1,60}").matches(e.name)) {"Unsafe ZIP entry"}
                    val max=if(e.name=="manifest.json")MAX_JSON.toLong() else MAX_RAW
                    val target=File(staging,e.name)
                    target.parentFile?.mkdirs()
                    var per=0L
                    FileOutputStream(target).use {out ->val buf=ByteArray(8192)
                        while(true){val n=input.read(buf);if(n<0)break;per+=n;total+=n
                            require(per<=max && total<=MAX_BUNDLE+MAX_JSON) {"Transfer exceeds size limits"};out.write(buf,0,n)}
                    }
                    if(e.name=="manifest.json") {manifest=JSONObject(target.readText(Charsets.UTF_8))}
                    input.closeEntry()
                }
            }
            val m=manifest?:error("Missing manifest")
            require(m.getInt("protocol")==1){"Unsupported report protocol"}
            val id=m.getString("reportId");require(hexRegex.matches(id)){"Invalid report ID"}
            val device=m.getString("deviceId");require(Regex("[a-zA-Z0-9-]{8,64}").matches(device)){"Invalid device ID"}
            require(m.getString("session")==expectedSession && m.getString("role")==expectedRole){"Session or device role does not match Controller"}
            val metadataFiles=m.getJSONArray("attachments")
            require(metadataFiles.length()<=1500 && metadataFiles.length()==encountered.size-1){"Missing or additional raw files"}
            val sourceMap=mutableMapOf<String,String>()
            for(i in 0 until metadataFiles.length()) {
                val item=metadataFiles.getJSONObject(i);val entry=item.getString("entry")
                require(entry in encountered && entry.startsWith("raw/")){"Missing attached report"}
                val file=File(staging,entry)
                require(file.length()==item.getLong("size") && shaFile(file)==item.getString("sha256")){"Original report integrity check failed"}
                sourceMap[item.getString("source")]=entry
            }
            val payload=m.getJSONArray("observations")
            require(payload.length() in 1..1500){"Invalid measurements count"}
            val originalRows=(0 until payload.length()).map {i->decode(payload.getJSONObject(i))}
            originalRows.forEach {original->
                require(original.session==expectedSession&&original.role==expectedRole&&original.date==m.getString("date") &&
                    original.metricId.let{ExperienceMetrics.find(it)!=null}&&original.value.isFinite() &&
                    original.attempts>0 && original.id.length<=500){"Invalid metric or identity"}
            }
            val allChecksums=(0 until metadataFiles.length()).map{metadataFiles.getJSONObject(it).getString("sha256")}
            require(reportIdFor(device,m.getString("session"),m.getString("role"),m.getString("date"),
                m.getString("scenario"),m.getString("operator"),m.getString("rat"),originalRows,allChecksums)==id){
                "Report identity does not match its contents"}
            val rows=originalRows.map{original->
                val file=sourceMap[original.source]?.let{File(destination,id+"/"+it).absolutePath}
                original.copy(id="LAN_${device}_${original.id}",dataOrigin="LAN_${original.dataOrigin}",
                    source=file?:"LAN_AGENT:$device:${original.source.take(180)}",
                    deviceId=device,deviceModel=m.optString("model").ifBlank{original.deviceModel},
                    runId=original.runId.ifBlank{original.source})
            }
            val finalDir=File(destination,id)
            if(finalDir.exists()) {
                require(File(finalDir,"manifest.json").isFile){"Existing transfer incomplete: review before retry"}
                // A previous process may have stopped after archiving and before data persistence.
                // Re-applying deterministic IDs is safe and recovers that interrupted import.
                staging.deleteRecursively()
            } else {
                check(staging.renameTo(finalDir)){"Could not commit received files"}
            }
            // Save is synchronous; Agent receives ACK only after the verified archive and rows exist.
            val added=repo.addRows(rows)
            return added to "Received $added readings, ${metadataFiles.length()} original files (${id.take(12)}). " +
                if(metadataFiles.length()==0)"No accessible raw files were included." else "Originals archived unchanged."
        } finally {if(staging.exists())staging.deleteRecursively()}
    }
    fun fetch(c:Context,repo:ExperienceRepositoryV130,address:String,token:String,session:String,role:String):String {
        val ip=safeAddress(address);require(token.matches(Regex("[A-Z2-9]{12}"))){"Enter the 12-character pairing code"}
        require(role in ExperienceAnalyzer.roles && session.isNotBlank())
        val cm=c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi=cm.allNetworks.firstOrNull {network -> cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true }
        // Hotspot host may expose a local AP interface without a TRANSPORT_WIFI client network.
        require(wifi!=null || localAddresses().isNotEmpty()) { "Connect to a trusted Wi-Fi or enable a local hotspot" }
        fun request(path:String,method:String="GET",headers:Map<String,String> = emptyMap()):HttpURLConnection {
            val url=URL("http://$ip:$PORT$path")
            val conn= (wifi?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.requestMethod=method;conn.connectTimeout=9000;conn.readTimeout=60000
            conn.setRequestProperty("X-CellTracker-Key",token)
            headers.forEach{(k,v)->conn.setRequestProperty(k,v)}
            return conn
        }
        val temp=File(c.cacheDir,"lan_received_${UUID.randomUUID()}.zip")
        try {
            val conn=request("/v1/bundle")
            val expected=try{
                if(conn.responseCode!=200)error("Agent refused: HTTP ${conn.responseCode}; confirm code and sharing state")
                val reported=conn.getHeaderField("X-CellTracker-Report-ID").orEmpty()
                require(hexRegex.matches(reported)){"Missing report identity"}
                val declared=conn.contentLengthLong
                require(declared in 1..(MAX_BUNDLE+MAX_JSON+2_000_000L)){"Invalid download length"}
                conn.inputStream.use{input-> FileOutputStream(temp).use{out->
                    val buf=ByteArray(8192);var total=0L
                    while(true){val n=input.read(buf);if(n<0)break;total+=n
                        require(total<=MAX_BUNDLE+MAX_JSON+2_000_000L){"Download too large"};out.write(buf,0,n)}
                    require(total==declared){"Download incomplete"}
                }}
                reported
            } finally {conn.disconnect()}
            // Entire ZIP verified before repository rows are committed.
            val status=receive(c,repo,temp,session,role)
            ZipInputStream(FileInputStream(temp)).use {z->
                val e=z.nextEntry?:error("Corrupt archive")
                require(e.name=="manifest.json")
                val raw=java.io.ByteArrayOutputStream()
                val buf=ByteArray(8192)
                while(true){val n=z.read(buf);if(n<0)break
                    require(raw.size()+n<=MAX_JSON){"Manifest too large"};raw.write(buf,0,n)}
                require(JSONObject(raw.toString("UTF-8")).getString("reportId")==expected){"Receipt identity mismatch"}
            }
            val receipt=shaFile(temp)
            val ack=request("/v1/ack","POST",mapOf("X-Report-ID" to expected,"X-Receipt-SHA256" to receipt))
            val ackOk=try{ack.responseCode==200}finally{ack.disconnect()}
            return "${status.second} " + if(ackOk)"Agent acknowledged Synced." else "Saved locally, but Agent acknowledgement failed; safe to retry."
        } finally {temp.delete()}
    }
}

/** Agent-side temporary authenticated endpoint, explicitly enabled only after testing. */
internal class ExperienceLanShareV130(private val context:Context) {
    private var socket:ServerSocket?=null
    private val active=AtomicInteger(0)
    private val workers=Executors.newFixedThreadPool(3)
    @Volatile private var bundle:ExperienceLanV130.Bundle?=null
    @Volatile private var code=""
    @Volatile private var failed=0
    val isSharing get()=socket?.isClosed==false
    val accessCode get()=code
    val report get()=bundle
    @Volatile private var servingAddress=""
    val addressText get()=servingAddress.ifBlank{"No private Wi-Fi address detected"}
    val isSynced get()=bundle?.id?.let{context.getSharedPreferences("experience_lan_v130",0).getBoolean("synced_$it",false)}?:false
    fun start(session:String,date:String,scenario:String,operator:String,rat:String,role:String):ExperienceLanV130.Bundle {
        stop()
        val prepared=ExperienceLanV130.prepare(context,session,date,scenario,operator,rat,role)
        try {
            val ip=ExperienceLanV130.localAddresses().firstOrNull()
                ?:error("No usable Wi-Fi/hotspot address. Connect to local Wi-Fi after testing.")
            val listener=ServerSocket(ExperienceLanV130.PORT,8,java.net.InetAddress.getByName(ip))
            servingAddress=ip
            bundle=prepared;failed=0;code=ExperienceLanV130.randomCode();socket=listener
            Thread({while(!listener.isClosed){val peer=try{listener.accept()}catch(_:Exception){break}
                if(active.incrementAndGet()>3){active.decrementAndGet();runCatching{peer.close()};continue}
                workers.execute{try{handle(peer)}finally{active.decrementAndGet()}}
            }},"CellTracker-LAN-Agent").apply{isDaemon=true;start()}
            return prepared
        }catch(e:Exception){prepared.zip.delete();bundle=null;servingAddress="";throw e}
    }
    private fun handle(peer:Socket){
        peer.use{s->try {
            s.soTimeout=12000
            val input=BufferedReader(InputStreamReader(s.getInputStream(),Charsets.UTF_8))
            val request=input.readLine()?.take(200)?:return
            val path=request.split(' ').getOrNull(1).orEmpty()
            var key="";var receipt="";var reportId="";var headerCount=0
            while(true){val line=input.readLine()?:return
                if(line.isBlank())break
                if(++headerCount>35||line.length>2048)return
                when {
                    line.startsWith("X-CellTracker-Key:",true)->key=line.substringAfter(':').trim()
                    line.startsWith("X-Receipt-SHA256:",true)->receipt=line.substringAfter(':').trim()
                    line.startsWith("X-Report-ID:",true)->reportId=line.substringAfter(':').trim()
                }
            }
            val authenticated=MessageDigest.isEqual(key.toByteArray(Charsets.UTF_8),code.toByteArray(Charsets.UTF_8))
            if(failed>=12 || !authenticated){failed++;response(s,403,"Forbidden");return}
            val b=bundle?:return response(s,503,"No active report")
            if(request.startsWith("GET ")&&path=="/v1/bundle"){
                val out=s.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n"+
                    "Content-Length: ${b.zip.length()}\r\nX-CellTracker-Report-ID: ${b.id}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                FileInputStream(b.zip).use{it.copyTo(out,8192)};out.flush()
            }else if(request.startsWith("POST ")&&path=="/v1/ack") {
                if(reportId==b.id&&receipt==b.digest){
                    val saved=context.getSharedPreferences("experience_lan_v130",0).edit().putBoolean("synced_${b.id}",true).commit()
                    response(s,if(saved)200 else 507,if(saved)"Saved" else "Storage failed")
                }else response(s,409,"Receipt mismatch")
            } else response(s,404,"Not found")
        }catch(_:Exception) { /* Peer can disconnect; Agent retains local reports and remains shareable. */ } }
    }
    private fun response(s:Socket,status:Int,message:String){
        val body=message.toByteArray(Charsets.UTF_8)
        s.getOutputStream().write(("HTTP/1.1 $status $message\r\nContent-Type: text/plain\r\n"+
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()+body)
    }
    fun stop(){runCatching{socket?.close()};socket=null;code="";servingAddress="";bundle?.zip?.delete();bundle=null}
    fun dispose(){stop();workers.shutdownNow()}
}
