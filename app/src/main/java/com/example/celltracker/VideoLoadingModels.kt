package com.example.celltracker

data class VideoLoadingConfig(
    val count:Int=10,
    val timeoutMs:Long=15000,
    val returnWaitMs:Long=2000,
    val autoRecord:Boolean=true,
    val semiAuto:Boolean=false
)
data class VideoLoadingSample(
    val sequence:Int, val title:String, val startMs:Long, val loadedMs:Long, val delayMs:Long?,
    val result:String, val detection:String, val snapshot:PingNetworkSnapshot=PingNetworkSnapshot(),
    val startElapsedMs:Long=0L, val loadedElapsedMs:Long=0L, val t0Source:String=""
)
data class VideoLoadingDetail(val path:String,val startedAt:Long,val endedAt:Long,val status:String,val samples:List<VideoLoadingSample>,val recordingPath:String?)
