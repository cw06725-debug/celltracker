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
    data class Entry(val name:String,val mode:Int,val size:Long) {
        val isDir get()=(mode and 0xF000)==0x4000
        val isFile get()=(mode and 0xF000)==0x8000
    }
    private fun le(v:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun exact(i:InputStream,n:Int):ByteArray { val b=ByteArray(n);var o=0;while(o<n){val r=i.read(b,o,n-o);if(r<0)throw EOFException("ADB Sync stream closed ($o/$n bytes)");o+=r};return b }
    private fun int(i:InputStream)=ByteBuffer.wrap(exact(i,4)).order(ByteOrder.LITTLE_ENDIAN).int
    private fun tag(i:InputStream)=String(exact(i,4),Charsets.US_ASCII)
    private fun request(o:OutputStream,id:String,path:String){val p=path.toByteArray();require(p.size<=1024);o.write(id.toByteArray(Charsets.US_ASCII));o.write(le(p.size));o.write(p);o.flush()}
    private fun fail(i:InputStream):Nothing{val n=int(i);error("ADB Sync FAIL: "+String(exact(i,n),Charsets.UTF_8))}
    private fun list(i:InputStream,o:OutputStream,path:String):List<Entry>{
        request(o,"LIST",path);val r=ArrayList<Entry>()
        while(true) when(val id=tag(i)){
            "DONE"->{int(i);return r}
            "FAIL"->fail(i)
            "DENT"->{val mode=int(i);val size=int(i).toLong() and 0xffffffffL;int(i);val n=int(i);val name=String(exact(i,n),Charsets.UTF_8);if(name!="."&&name!="..")r+=Entry(name,mode,size)}
            else->error("Unexpected LIST response: $id")
        }
    }
    private fun recv(i:InputStream,o:OutputStream,remote:String,dir:String,name:String,onBytes:(Int)->Unit){
        val resolver=context.contentResolver
        val v=ContentValues().apply{
            put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT/$dir");put(MediaStore.Downloads.IS_PENDING,1)
        }
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Cannot create $name")
        try{
            resolver.openOutputStream(uri,"w")!!.use{local->
                request(o,"RECV",remote)
                while(true) when(val id=tag(i)){
                    "DONE"->{int(i);break}
                    "FAIL"->fail(i)
                    "DATA"->{var left=int(i);require(left in 0..65536);val b=ByteArray(65536);while(left>0){val n=i.read(b,0,minOf(left,b.size));if(n<0)throw EOFException("Stream closed during $remote");local.write(b,0,n);left-=n;onBytes(n)}}
                    else->error("Unexpected RECV response: $id")
                }
                local.flush()
            }
            v.clear();v.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(uri,v,null,null)
        }catch(e:Throwable){resolver.delete(uri,null,null);throw e}
    }
    fun pullTree(root:String,session:String,onProgress:(SyncPullProgress)->Unit):Pair<Long,Long>{
        val s=CellTrackerAdbConnectionManager.getInstance(context).openStream("sync:")
        val i=s.openInputStream();val o=s.openOutputStream()
        data class Node(val remote:String,val rel:String)
        val q=ArrayDeque<Node>();q.add(Node(root.trimEnd('/'),""));var files=0L;var bytes=0L
        try{
            while(q.isNotEmpty()){
                val n=q.removeFirst()
                for(e in list(i,o,n.remote)){
                    val remote="${n.remote}/${e.name}";val rel=if(n.rel.isBlank())e.name else "${n.rel}/${e.name}"
                    if(e.isDir)q.add(Node(remote,rel)) else if(e.isFile){
                        onProgress(SyncPullProgress(files,bytes,remote))
                        val x=rel.lastIndexOf('/');val sub=if(x>=0)rel.substring(0,x) else "";val name=if(x>=0)rel.substring(x+1) else rel
                        val dir=if(sub.isBlank())session else "$session/$sub"
                        recv(i,o,remote,dir,name){k->bytes+=k;onProgress(SyncPullProgress(files,bytes,remote))}
                        files++;onProgress(SyncPullProgress(files,bytes,remote))
                    }
                }
            }
            return files to bytes
        }finally{
            runCatching{o.write("QUIT".toByteArray(Charsets.US_ASCII));o.write(le(0));o.flush()}
            runCatching{i.close()};runCatching{o.close()}
        }
    }
}
