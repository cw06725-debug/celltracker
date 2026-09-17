package com.example.celltracker

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.math.min

data class UsbAdbInfo(val name:String,val vendorId:Int,val productId:Int)

class UsbAdbHost(private val context:Context) {
    companion object {
        private const val A_CNXN=0x4e584e43; private const val A_AUTH=0x48545541
        private const val A_OPEN=0x4e45504f; private const val A_OKAY=0x59414b4f
        private const val A_CLSE=0x45534c43; private const val A_WRTE=0x45545257
        private const val AUTH_TOKEN=1; private const val AUTH_SIGNATURE=2; private const val AUTH_RSAPUBLICKEY=3
        private const val MAX=256*1024
        @Volatile var instance:UsbAdbHost?=null
        fun get(context:Context)=instance ?: synchronized(this){instance ?: UsbAdbHost(context.applicationContext).also{instance=it}}
    }
    private val manager=context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device:UsbDevice?=null; private var intf:UsbInterface?=null
    private var input:UsbEndpoint?=null; private var output:UsbEndpoint?=null; private var conn:UsbDeviceConnection?=null
    private var localId=1
    @Volatile var connected=false; private set
    @Volatile var authorized=false; private set

    fun findDevice():Pair<UsbDevice,UsbInterface>? {
        for(d in manager.deviceList.values) for(i in 0 until d.interfaceCount){
            val f=d.getInterface(i)
            if(f.interfaceClass==0xff && f.interfaceSubclass==0x42 && f.interfaceProtocol==0x01) return d to f
        }
        return null
    }
    fun info():UsbAdbInfo?=findDevice()?.first?.let{UsbAdbInfo(it.productName ?: it.deviceName,it.vendorId,it.productId)}
    fun hasPermission()=findDevice()?.first?.let{manager.hasPermission(it)}==true
    fun requestPermission(action:String){
        val d=findDevice()?.first ?: error("No USB ADB device detected. Enable USB debugging on REF and connect by OTG/data cable.")
        val pi=PendingIntent.getBroadcast(context,7711,Intent(action).setPackage(context.packageName),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        manager.requestPermission(d,pi)
    }
    private fun setup(){
        val pair=findDevice() ?: error("No ADB USB interface (FF/42/01) detected")
        device=pair.first;intf=pair.second
        val f=pair.second
        for(n in 0 until f.endpointCount){val e=f.getEndpoint(n);if(e.type==UsbConstants.USB_ENDPOINT_XFER_BULK){if(e.direction==UsbConstants.USB_DIR_IN)input=e else output=e}}
        check(input!=null && output!=null){"ADB bulk endpoints not found"}
        check(manager.hasPermission(pair.first)){"USB permission not granted"}
        conn=manager.openDevice(pair.first) ?: error("Cannot open USB device")
        check(conn!!.claimInterface(f,true)){"Cannot claim ADB USB interface"}
    }
    private data class P(val cmd:Int,val a0:Int,val a1:Int,val data:ByteArray)
    private fun checksum(b:ByteArray)=b.fold(0){a,x->a+(x.toInt() and 255)}
    private fun writePacket(cmd:Int,a0:Int,a1:Int,data:ByteArray=byteArrayOf()){
        val h=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(checksum(data)).putInt(cmd xor -1)
        writeAll(h.array());if(data.isNotEmpty())writeAll(data)
    }
    private fun writeAll(b:ByteArray){var o=0;while(o<b.size){val n=conn!!.bulkTransfer(output,b,o,b.size-o,5000);if(n<=0)error("USB ADB write failed");o+=n}}
    private fun readExact(n:Int,timeout:Int=5000):ByteArray{val r=ByteArray(n);var o=0;while(o<n){val tmp=ByteArray(min(16384,n-o));val k=conn!!.bulkTransfer(input,tmp,tmp.size,timeout);if(k<=0)error("USB ADB read timeout");System.arraycopy(tmp,0,r,o,k);o+=k};return r}
    private fun readPacket(timeout:Int=5000):P{
        val h=ByteBuffer.wrap(readExact(24,timeout)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd=h.int;val a0=h.int;val a1=h.int;val len=h.int;val sum=h.int;val magic=h.int
        check(magic==(cmd xor -1)){"Invalid ADB packet magic"};check(len in 0..MAX){"Invalid ADB payload $len"}
        val d=if(len>0)readExact(len,timeout) else byteArrayOf();check(checksum(d)==sum){"ADB checksum mismatch"}
        return P(cmd,a0,a1,d)
    }
    fun connect():String{
        close();setup();connected=false;authorized=false
        writePacket(A_CNXN,0x01000001,MAX,"host::CellTracker\u0000".toByteArray())
        val key=CellTrackerAdbConnectionManager.getInstance(context)
        repeat(8){
            val p=readPacket(8000)
            when(p.cmd){
                A_CNXN->{connected=true;authorized=true;return String(p.data).trimEnd('\u0000')}
                A_AUTH->{
                    if(p.a0==AUTH_TOKEN){
                        if(it==0){
                            val sig=Signature.getInstance("NONEwithRSA");sig.initSign(key.privateKey);sig.update(p.data)
                            writePacket(A_AUTH,AUTH_SIGNATURE,0,sig.sign())
                        }else writePacket(A_AUTH,AUTH_RSAPUBLICKEY,0,adbPublicKey(key.certificate.publicKey as RSAPublicKey))
                    }
                }
            }
        }
        error("USB ADB authorization timed out. Accept 'Allow USB debugging' on REF and retry.")
    }
    private fun adbPublicKey(k:RSAPublicKey):ByteArray{
        // Android RSAPublicKey wire structure: 2048-bit modulus in little endian + exponent.
        val words=64;val n0inv=0 // adbd accepts the standard adb public-key blob; calculate Montgomery fields below.
        val mod=toLe(k.modulus.toByteArray(),256)
        val r32=java.math.BigInteger.ONE.shiftLeft(32)
        val n0=k.modulus.and(r32.subtract(java.math.BigInteger.ONE))
        val inv=n0.modInverse(r32).negate().and(r32.subtract(java.math.BigInteger.ONE)).toLong().toInt()
        val rr=java.math.BigInteger.ONE.shiftLeft(4096).mod(k.modulus)
        val rrle=toLe(rr.toByteArray(),256)
        val b=ByteBuffer.allocate(4+4+256+256+4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(inv).put(mod).put(rrle).putInt(k.publicExponent.toInt())
        val base=Base64.getEncoder().encodeToString(b.array())
        return "$base CellTracker@android\u0000".toByteArray()
    }
    private fun toLe(src:ByteArray,size:Int):ByteArray{
        val clean=if(src.size>size)src.copyOfRange(src.size-size,src.size) else src
        val out=ByteArray(size);for(i in clean.indices)out[i]=clean[clean.size-1-i];return out
    }
    fun shell(command:String,onData:(ByteArray)->Unit){
        check(connected){"USB ADB is not connected"}
        val local=localId++;writePacket(A_OPEN,local,0,("shell:$command\u0000").toByteArray())
        var remote=0
        while(true){
            val p=readPacket(0)
            when(p.cmd){
                A_OKAY->{if(remote==0)remote=p.a0}
                A_WRTE->{remote=p.a0;onData(p.data);writePacket(A_OKAY,local,remote)}
                A_CLSE->{writePacket(A_CLSE,local,p.a0);return}
            }
        }
    }
    fun close(){runCatching{intf?.let{conn?.releaseInterface(it)}};runCatching{conn?.close()};conn=null;connected=false}
}
