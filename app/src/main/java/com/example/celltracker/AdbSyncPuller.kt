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
data class SyncPullResult(val found:Long,val pulled:Long,val skipped:Long,val bytes:Long,val files:List<SyncPulledFile>)
class AdbSyncPuller(private val context:Context){
 data class Entry(val name:String,val mode:Int,val size:Long){val type get()=mode and 0xF000;val isDir get()=type==0x4000;val isFile get()=type==0x8000;val isLink get()=type==0xA000}
 private fun le(v:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
 private fun exact(i:InputStream,n:Int):ByteArray{val b=ByteArray(n);var o=0;while(o<n){val r=i.read(b,o,n-o);if(r<0)throw EOFException("ADB Sync stream ended ($o/$n bytes)");o+=r};return b}
 private fun int(i:InputStream)=ByteBuffer.wrap(exact(i,4)).order(ByteOrder.LITTLE_ENDIAN).int
 private fun request(o:OutputStream,id:String,path:String){val p=path.toByteArray(Charsets.UTF_8);val b=ByteArrayOutputStream();b.write(id.toByteArray());b.write(le(p.size));b.write(p);o.write(b.toByteArray());o.flush()}
 private fun fail(i:InputStream):Nothing{val n=int(i);error("ADB Sync FAIL: "+String(exact(i,n),Charsets.UTF_8))}
 private inline fun <T> session(block:(InputStream,OutputStream)->T):T{val s=CellTrackerAdbConnectionManager.getInstance(context).openStream("sync:");val i=s.openInputStream();val o=s.openOutputStream();try{return block(i,o)}finally{runCatching{s.close()}}}
 private fun list(path:String):List<Entry> = session{i,o->request(o,"LIST",path);val r=ArrayList<Entry>();while(true){when(String(exact(i,4),Charsets.US_ASCII)){"DONE"->{int(i);return@session r};"FAIL"->fail(i);"DENT"->{val mode=int(i);val size=int(i).toLong() and 0xffffffffL;int(i);val n=int(i);val name=String(exact(i,n),Charsets.UTF_8);if(name!="."&&name!="..")r+=Entry(name,mode,size)};else->error("Unexpected ADB Sync LIST response for $path")}};@Suppress("UNREACHABLE_CODE") r}
 private fun createFile(remote:String,dir:String,name:String,onBytes:(Int)->Unit):Uri{
  val r=context.contentResolver;val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT/$dir");put(MediaStore.Downloads.IS_PENDING,1)}
  val uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Cannot create $name")
  try{r.openOutputStream(uri,"w")!!.use{local->session{i,o->request(o,"RECV",remote);while(true){when(String(exact(i,4),Charsets.US_ASCII)){"DONE"->{int(i);return@session};"FAIL"->fail(i);"DATA"->{var left=int(i);val b=ByteArray(65536);while(left>0){val n=i.read(b,0,minOf(left,b.size));if(n<0)throw EOFException("ADB Sync ended during $remote");local.write(b,0,n);left-=n;onBytes(n)}};else->error("Unexpected ADB Sync RECV response for $remote")}}}};v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);r.update(uri,v,null,null);return uri}catch(e:Throwable){r.delete(uri,null,null);throw e}
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
    onProgress(SyncPullProgress(found, pulled, skipped, bytes, node.remote))
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
      // Vendor debug trees may expose useful folders through symlinks.
      // Queue it once and let LIST determine whether the target is traversable.
      q.add(Node(remote, rel))
     }

     else -> {
      skipped++
      onProgress(SyncPullProgress(found, pulled, skipped, bytes, remote))
     }
    }
   }
  }

  return SyncPullResult(found, pulled, skipped, bytes, files)
 }
 fun compress(sessionName:String,result:SyncPullResult,deleteSource:Boolean,onProgress:(String)->Unit):String{
  val resolver=context.contentResolver;val zipName="$sessionName.zip";val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,zipName);put(MediaStore.Downloads.MIME_TYPE,"application/zip");put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT");put(MediaStore.Downloads.IS_PENDING,1)};val zipUri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Cannot create ZIP")
  try{resolver.openOutputStream(zipUri,"w")!!.use{raw->ZipOutputStream(BufferedOutputStream(raw)).use{zip->result.files.forEachIndexed{idx,f->onProgress("Compressing ${idx+1}/${result.files.size}: ${f.relative}");zip.putNextEntry(ZipEntry(f.relative));resolver.openInputStream(f.uri)!!.use{it.copyTo(zip,65536)};zip.closeEntry()}}};v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(zipUri,v,null,null);if(deleteSource)result.files.forEach{resolver.delete(it.uri,null,null)};return "Download/CellTracker/Logs/DUT/$zipName"}catch(e:Throwable){resolver.delete(zipUri,null,null);throw e}
 }
}
