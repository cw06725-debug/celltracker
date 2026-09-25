package com.example.celltracker
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SyncPullProgress(val found:Long,val filesDone:Long,val skipped:Long,val bytesDone:Long,val current:String,val phase:String="Pulling files…")
data class SyncPulledFile(val uri:Uri,val relative:String)
data class SyncPullResult(val found:Long,val pulled:Long,val skipped:Long,val bytes:Long,val files:List<SyncPulledFile>,val symlinks:Long=0,val listFailed:Long=0)
class AdbSyncPuller(private val context:Context){
 data class Entry(val name:String,val mode:Int,val size:Long){val type get()=mode and 0xF000;val isDir get()=type==0x4000;val isFile get()=type==0x8000;val isLink get()=type==0xA000}
 private fun le(v:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
 private fun exact(i:InputStream,n:Int):ByteArray{val b=ByteArray(n);var o=0;while(o<n){val r=i.read(b,o,n-o);if(r<0)throw EOFException("ADB Sync stream ended ($o/$n bytes)");o+=r};return b}
 private fun int(i:InputStream)=ByteBuffer.wrap(exact(i,4)).order(ByteOrder.LITTLE_ENDIAN).int
 private fun request(o:OutputStream,id:String,path:String){val p=path.toByteArray(Charsets.UTF_8);val b=ByteArrayOutputStream();b.write(id.toByteArray());b.write(le(p.size));b.write(p);o.write(b.toByteArray());o.flush()}
 private fun fail(i:InputStream):Nothing{val n=int(i);error("ADB Sync FAIL: "+String(exact(i,n),Charsets.UTF_8))}
 private inline fun <T> session(block:(InputStream,OutputStream)->T):T{val s=CellTrackerAdbConnectionManager.getInstance(context).openStream("sync:");val i=s.openInputStream();val o=s.openOutputStream();try{return block(i,o)}finally{runCatching{s.close()}}}
 private fun list(path:String):List<Entry> = session{i,o->request(o,"LIST",path);val r=ArrayList<Entry>();while(true){when(String(exact(i,4),Charsets.US_ASCII)){"DONE"->{int(i);return@session r};"FAIL"->fail(i);"DENT"->{val mode=int(i);val size=int(i).toLong() and 0xffffffffL;int(i);val n=int(i);val name=String(exact(i,n),Charsets.UTF_8);if(name!="."&&name!="..")r+=Entry(name,mode,size)};else->error("Unexpected ADB Sync LIST response for $path")}};@Suppress("UNREACHABLE_CODE") r}
 private fun createFile(remote:String,dir:String,name:String,baseRoot:String="${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT",onBytes:(Int)->Unit):Uri{
  val r=context.contentResolver;val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");put(MediaStore.Downloads.RELATIVE_PATH, if(dir.isBlank()) baseRoot else "$baseRoot/$dir");put(MediaStore.Downloads.IS_PENDING,1)}
  val uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Cannot create $name")
  try{r.openOutputStream(uri,"w")!!.use{local->session{i,o->request(o,"RECV",remote);while(true){when(String(exact(i,4),Charsets.US_ASCII)){"DONE"->{int(i);return@session};"FAIL"->fail(i);"DATA"->{var left=int(i);val b=ByteArray(65536);while(left>0){val n=i.read(b,0,minOf(left,b.size));if(n<0)throw EOFException("ADB Sync ended during $remote");local.write(b,0,n);left-=n;onBytes(n)}};else->error("Unexpected ADB Sync RECV response for $remote")}}}};v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);r.update(uri,v,null,null);return uri}catch(e:Throwable){r.delete(uri,null,null);throw e}
 }
 private fun resolveLink(remote:String):String? {
  return try {
   val manager=CellTrackerAdbConnectionManager.getInstance(context)
   val stream=manager.openStream("shell:readlink -f '${remote.replace("'", "'\\''")}'")
   val input=stream.openInputStream()
   val out=ByteArrayOutputStream()
   val buf=ByteArray(4096)
   val deadline=System.currentTimeMillis()+3000
   while(System.currentTimeMillis()<deadline){
    if(input.available()>0){val n=input.read(buf);if(n<0)break;out.write(buf,0,n)}
    else Thread.sleep(20)
   }
   runCatching{stream.close()}
   out.toString(Charsets.UTF_8.name()).trim().lineSequence().firstOrNull()?.takeIf{it.startsWith("/")}
  } catch(_:Throwable){ null }
 }
 fun pullTree(
  root: String,
  sessionDir: String,
  onProgress: (SyncPullProgress) -> Unit
 ): SyncPullResult {
  data class Node(val remote: String, val rel: String)

  val q = ArrayDeque<Node>()
  q.add(Node(root.trimEnd('/'), ""))

  var found = 0L
  var pulled = 0L
  var skipped = 0L
  var symlinks = 0L
  var listFailed = 0L
  var bytes = 0L
  val files = ArrayList<SyncPulledFile>()
  val visited = HashSet<String>()

  while (q.isNotEmpty()) {
   val node = q.removeFirst()
   if (!visited.add(node.remote)) continue

   val entries = try {
    list(node.remote)
   } catch (e: Throwable) {
    skipped++
    listFailed++
    onProgress(SyncPullProgress(found, pulled, skipped, bytes, node.remote, "LIST failed"))
    continue
   }

   for (entry in entries) {
    found++
    val remote = "${node.remote}/${entry.name}"
    val rel = if (node.rel.isBlank()) {
     entry.name
    } else {
     "${node.rel}/${entry.name}"
    }

    when {
     entry.isDir -> {
      q.add(Node(remote, rel))
     }

     entry.isFile -> {
      onProgress(SyncPullProgress(found, pulled, skipped, bytes, remote))

      val slash = rel.lastIndexOf('/')
      val subDir = if (slash >= 0) {
       rel.substring(0, slash)
      } else {
       ""
      }
      val fileName = if (slash >= 0) {
       rel.substring(slash + 1)
      } else {
       rel
      }
      val outputDir = if (subDir.isBlank()) {
       sessionDir
      } else {
       "$sessionDir/$subDir"
      }

      val uri = createFile(remote, outputDir, fileName) { count ->
       bytes += count
       onProgress(SyncPullProgress(found, pulled, skipped, bytes, remote))
      }
      files += SyncPulledFile(uri, rel)
      pulled++
     }

     entry.isLink -> {
      symlinks++
      val target = resolveLink(remote)
      if (target != null) {
       // Preserve the link's visible relative name while traversing its resolved target.
       q.add(Node(target, rel))
      } else {
       skipped++
       onProgress(SyncPullProgress(found, pulled, skipped, bytes, remote, "Symlink unresolved"))
      }
     }

     else -> {
      skipped++
      onProgress(SyncPullProgress(found, pulled, skipped, bytes, remote))
     }
    }
   }
  }

  return SyncPullResult(found, pulled, skipped, bytes, files, symlinks, listFailed)
 }
 fun pullSingleFile(remote:String, relativeDir:String, outputName:String, onProgress:(SyncPullProgress)->Unit):SyncPulledFile {
  var bytes=0L
  val cleanDir=relativeDir.trim('/').replace("..", "_")
  val cleanName=outputName.replace(Regex("[\\/:*?\"<>|]+"), "_").ifBlank { "MFT_Report.xls" }
  onProgress(SyncPullProgress(1,0,0,0,remote,"Pulling file…"))
  val uri=createFile(remote, cleanDir, cleanName){n->
   bytes+=n
   onProgress(SyncPullProgress(1,0,0,bytes,remote,"Pulling file…"))
  }
  val size=context.contentResolver.openFileDescriptor(uri,"r")?.use{it.statSize}?:0L
  if(size<=0L){ context.contentResolver.delete(uri,null,null); error("Pulled file is empty") }
  onProgress(SyncPullProgress(1,1,0,size,remote,"Verified"))
  return SyncPulledFile(uri,cleanName)
 }



 fun pullSingleFileTo(remote:String, baseRelativePath:String, outputName:String, onProgress:(SyncPullProgress)->Unit):SyncPulledFile {
  var bytes=0L
  val cleanName=outputName.replace(Regex("[\\/:*?\"<>|]+"), "_").ifBlank { "MFT_Report.xls" }
  val baseRoot="${Environment.DIRECTORY_DOWNLOADS}/${baseRelativePath.trim('/')}"
  onProgress(SyncPullProgress(1,0,0,0,remote,"Pulling file…"))
  val uri=createFile(remote, "", cleanName, baseRoot){n->
   bytes+=n
   onProgress(SyncPullProgress(1,0,0,bytes,remote,"Pulling file…"))
  }
  val size=context.contentResolver.openFileDescriptor(uri,"r")?.use{it.statSize}?:0L
  if(size<=0L){ context.contentResolver.delete(uri,null,null); error("Pulled file is empty") }
  onProgress(SyncPullProgress(1,1,0,size,remote,"Verified"))
  return SyncPulledFile(uri,cleanName)
 }

 fun pullSingleFileUsb(remote:String, baseRelativePath:String, outputName:String, onProgress:(SyncPullProgress)->Unit):SyncPulledFile {
  val resolver=context.contentResolver
  val cleanName=outputName.replace(Regex("[\\/:*?\"<>|]+"), "_").ifBlank { "MFT_Report.xls" }
  val values=ContentValues().apply{
   put(MediaStore.Downloads.DISPLAY_NAME,cleanName)
   put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
   put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/${baseRelativePath.trim('/')}")
   put(MediaStore.Downloads.IS_PENDING,1)
  }
  val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create $cleanName")
  var bytes=0L
  try{
   onProgress(SyncPullProgress(1,0,0,0,remote,"Pulling via USB…"))
   resolver.openOutputStream(uri,"w")!!.use{out->
    val esc=remote.replace("'","'\\''")
    UsbAdbHost.get(context).shell("cat '$esc'"){chunk->
     out.write(chunk)
     bytes+=chunk.size
     onProgress(SyncPullProgress(1,0,0,bytes,remote,"Pulling via USB…"))
    }
   }
   values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(uri,values,null,null)
   val size=resolver.openFileDescriptor(uri,"r")?.use{it.statSize}?:0L
   if(size<=0L) error("Pulled file is empty")
   onProgress(SyncPullProgress(1,1,0,size,remote,"Verified"))
   return SyncPulledFile(uri,cleanName)
  }catch(e:Throwable){
   resolver.delete(uri,null,null)
   throw e
  }
 }

 data class ZipResult(val path:String,val deleted:Int,val deleteFailed:Int,val zipBytes:Long)
 fun compress(sessionName:String,result:SyncPullResult,deleteSource:Boolean,onProgress:(String)->Unit):ZipResult{
  val resolver=context.contentResolver
  val zipName="$sessionName.zip"
  val values=ContentValues().apply{
   put(MediaStore.Downloads.DISPLAY_NAME,zipName)
   put(MediaStore.Downloads.MIME_TYPE,"application/zip")
   put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT")
   put(MediaStore.Downloads.IS_PENDING,1)
  }
  val zipUri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create ZIP")
  try{
   resolver.openOutputStream(zipUri,"w")!!.use{raw->
    ZipOutputStream(BufferedOutputStream(raw)).use{zip->
     result.files.forEachIndexed{idx,f->
      onProgress("Compressing ${idx+1}/${result.files.size}: ${f.relative}")
      zip.putNextEntry(ZipEntry(f.relative))
      resolver.openInputStream(f.uri)!!.use{it.copyTo(zip,65536)}
      zip.closeEntry()
     }
    }
   }
   values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(zipUri,values,null,null)
   var deleted=0;var failed=0
   if(deleteSource){
    result.files.forEachIndexed{idx,f->
     onProgress("Deleting source ${idx+1}/${result.files.size}")
     try{if(resolver.delete(f.uri,null,null)>0)deleted++ else failed++}catch(_:Throwable){failed++}
    }
   }
   val zipBytes=resolver.openFileDescriptor(zipUri,"r")?.use{it.statSize}?:0L
   return ZipResult("Download/CellTracker/Logs/DUT/$zipName",deleted,failed,zipBytes)
  }catch(e:Throwable){resolver.delete(zipUri,null,null);throw e}
 }

}
