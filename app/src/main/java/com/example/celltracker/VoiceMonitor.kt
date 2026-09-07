package com.example.celltracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.*

data class VoiceCheckResult(val direction:String,val result:String,val toneHz:Int,val levelDb:Double,val snrDb:Double,val durationMs:Long,val detail:String="")

object VoiceMonitor {
    const val TONE_A_TO_B = 1000
    const val TONE_B_TO_A = 1400

    fun setSpeakerphone(context: Context, enabled:Boolean) {
        runCatching {
            val am=context.getSystemService(AudioManager::class.java)
            am.mode=AudioManager.MODE_IN_COMMUNICATION
            if(android.os.Build.VERSION.SDK_INT>=31) {
                val target=am.availableCommunicationDevices.firstOrNull{it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
                if(enabled && target!=null) am.setCommunicationDevice(target) else if(!enabled) am.clearCommunicationDevice()
            } else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn=enabled }
        }
    }

    suspend fun playTone(hz:Int,durationMs:Long=1400L)=withContext(Dispatchers.IO){
        val rate=16000; val n=(rate*durationMs/1000).toInt(); val pcm=ShortArray(n)
        for(i in 0 until n){ val fade=min(1.0,min(i/320.0,(n-i-1)/320.0)); pcm[i]=(sin(2.0*PI*hz*i/rate)*0.28*Short.MAX_VALUE*fade).toInt().toShort() }
        val attrs=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt=AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val t=AudioTrack(attrs,fmt,pcm.size*2,AudioTrack.MODE_STATIC,AudioManager.AUDIO_SESSION_ID_GENERATE)
        try { t.write(pcm,0,pcm.size);t.play();delay(durationMs+100) } finally { runCatching{t.stop()};t.release() }
    }

    suspend fun detectTone(context:Context,hz:Int,direction:String,durationMs:Long=1900L):VoiceCheckResult=withContext(Dispatchers.IO){
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            return@withContext VoiceCheckResult(direction,"VOICE_CHECK_FAILED",hz,-120.0,0.0,durationMs,"RECORD_AUDIO permission missing")
        val rate=16000; val minBuf=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
        val r=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,minBuf*2)
        val samples=ArrayList<Short>((rate*durationMs/1000).toInt())
        try {
            r.startRecording(); val deadline=android.os.SystemClock.elapsedRealtime()+durationMs; val b=ShortArray(1024)
            while(android.os.SystemClock.elapsedRealtime()<deadline){val c=r.read(b,0,b.size);if(c>0)for(i in 0 until c)samples.add(b[i])}
        } catch(e:Exception){return@withContext VoiceCheckResult(direction,"VOICE_CHECK_FAILED",hz,-120.0,0.0,durationMs,e.message.orEmpty())}
        finally {runCatching{r.stop()};r.release()}
        if(samples.size<800)return@withContext VoiceCheckResult(direction,"VOICE_CHECK_FAILED",hz,-120.0,0.0,durationMs,"Insufficient audio samples")
        val x=ShortArray(samples.size){samples[it]}; val rms=sqrt(x.fold(0.0){a,v->a+v.toDouble()*v}/x.size)/32768.0
        val tone=goertzel(x,rate,hz); val side=(goertzel(x,rate,hz-180)+goertzel(x,rate,hz+180))/2.0
        val level=20*log10(rms.coerceAtLeast(1e-6)); val snr=10*log10((tone+1e-12)/(side+1e-12))
        val result=when { level < -48.0 || snr < 3.0 -> "NO_AUDIO"; snr < 9.0 -> "HIGH_NOISE"; else -> "VOICE_OK" }
        VoiceCheckResult(direction,result,hz,level,snr,durationMs,"level=${"%.1f".format(level)}dB snr=${"%.1f".format(snr)}dB")
    }

    private fun goertzel(x:ShortArray,rate:Int,hz:Int):Double{
        val w=2.0*PI*hz/rate; val coeff=2*cos(w);var s0=0.0;var s1=0.0;var s2=0.0
        for(v in x){s0=v/32768.0+coeff*s1-s2;s2=s1;s1=s0}; return (s1*s1+s2*s2-coeff*s1*s2)/x.size.coerceAtLeast(1)
    }
}
