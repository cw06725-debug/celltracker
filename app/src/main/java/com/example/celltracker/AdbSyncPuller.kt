package com.example.celltracker
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

data class SyncPullProgress(val filesDone:Long,val bytesDone:Long,val current:String)
class AdbSyncPuller(private val context:Context) {
    data class Entry(val name:String,val mode:Int,val size:Long){val isDir get()=(mode and 0xF000)==0x4000;val isFile get()=(mode and 0xF000)==0x8000}
    private fun le(v:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun exact(i:InputStream,n:Int):ByteArray{val b=ByteArray(n);var o=0;while(o<n){val r=i.read(b,o,n-o);if(r<0)throw EOFException("ADB Sync stream ended ($o/$n bytes)");o+=r};return b}
    private fun int(i:InputStream)=ByteBuffer.wrap(exact(i,4)).order(ByteOrder.LITTLE_ENDIAN).int
    private fun printable(b:ByteArray):String{val a=b.joinToString(""){x->val v=x.toInt() and 255;if(v in 32..126)v.toChar().toString() else "."};val h=b.joinToString(" "){String.format("%02X",it.toInt() and 255)};return "'$a' [$h]"}
    private fun request(o:OutputStream,id:String,path:String){val p=path.toByteArray(Charsets.UTF_8);require(p.size<=1024);val b=ByteArrayOutputStream();b.write(id.toByteArray(Charsets.US_ASCII));b.write(le(p.size));b.write(p);o.write(b.toByteArray());o.flush()}
    private fun fail(i:InputStream):Nothing{val n=int(i);error("ADB Sync FAIL: "+String(exact(i,n),Charsets.UTF_8))}
    private inline fun <T> session(block:(InputStream,OutputStream)->T):T{val s=CellTrackerAdbConnectionManager.getInstance(context).openStream("sync:");val i=s.openInputStream();val o=s.openOutputStream();try{return block(i,o)}finally{runCatching{s.close()}}}
    private fun list(path:String):List<Entry> = session{i,o->
        request(o,"LIST",path);val r=ArrayList<Entry>()
        while(true){val raw=exact(i,4);when(String(raw,Charsets.US_ASCII)){
            "DONE"->{int(i);return@session r}
            "FAIL"->fail(i)
            "DENT"->{val mode=int(i);val size=int(i).toLong() and 0xffffffffL;int(i);val n=int(i);require(n in 0..65535);val name=String(exact(i,n),Charsets.UTF_8);if(name!="."&&name!="..")r+=Entry(name,mode,size)}
            else->error("ADB Sync LIST protocol error: ${printable(raw)} · $path")
        }}
        @Suppress("UNREACHABLE_CODE") r
    }
    private fun recv(remote:String,dir:String,name:String,onBytes:(Int)->Unit){
        val resolver=context.contentResolver
        val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT/$dir");put(MediaStore.Downloads.IS_PENDING,1)}
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Cannot create $name")
        try{
            resolver.openOutputStream(uri,"w")!!.use{local->
                session{i,o->request(o,"RECV",remote);while(true){val raw=exact(i,4);when(String(raw,Charsets.US_ASCII)){
                    "DONE"->{int(i);return@session}
                    "FAIL"->fail(i)
                    "DATA"->{var left=int(i);require(left in 0..65536);val buf=ByteArray(65536);while(left>0){val n=i.read(buf,0,minOf(left,buf.size));if(n<0)throw EOFException("ADB Sync ended during $remote");local.write(buf,0,n);left-=n;onBytes(n)}}
                    else->error("ADB Sync RECV protocol error: ${printable(raw)} · $remote")
                }}}
                local.flush()
            }
            v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(uri,v,null,null)
        }catch(e:Throwable){resolver.delete(uri,null,null);throw e}
    }
    fun pullTree(root:String,sessionDir:String,onProgress:(SyncPullProgress)->Unit):Pair<Long,Long>{
        data class Node(val remote:String,val rel:String);val q=ArrayDeque<Node>();q.add(Node(root.trimEnd('/'),""));var files=0L;var bytes=0L
        while(q.isNotEmpty()){val n=q.removeFirst();for(e in list(n.remote)){val remote="${n.remote}/${e.name}";val rel=if(n.rel.isBlank())e.name else "${n.rel}/${e.name}";if(e.isDir)q.add(Node(remote,rel))else if(e.isFile){onProgress(SyncPullProgress(files,bytes,remote));val x=rel.lastIndexOf('/');val sub=if(x>=0)rel.substring(0,x)else "";val name=if(x>=0)rel.substring(x+1)else rel;val dir=if(sub.isBlank())sessionDir else "$sessionDir/$sub";recv(remote,dir,name){k->bytes+=k;onProgress(SyncPullProgress(files,bytes,remote))};files++;onProgress(SyncPullProgress(files,bytes,remote))}}}
        return files to bytes
    }
}
