package com.example.celltracker

data class WhatsAppSendConfig(
    val autoRecord:Boolean=true,
    val metadata:TestMetadata=TestMetadata(task="WhatsApp Image Send")
)

data class WhatsAppSendSample(
    val sequence:Int,
    val t0Ms:Long,
    val t1Ms:Long,
    val delayMs:Long,
    val snapshot:PingNetworkSnapshot=PingNetworkSnapshot(),
    val t0ElapsedMs:Long=0L,
    val t1ElapsedMs:Long=0L,
    val t0Source:String=""
)

data class WhatsAppSendDetail(
    val path:String,
    val startedAt:Long,
    val endedAt:Long,
    val status:String,
    val samples:List<WhatsAppSendSample>
)
