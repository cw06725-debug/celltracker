package com.example.celltracker
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ReviewedEventV1(val index:Int,val valid:Boolean,val t0:String,val t1:String,val note:String="")
data class ReviewStateV1(val confirmed:Boolean=false,val confirmedAt:Long=0L,val events:List<ReviewedEventV1> = emptyList())

object ReportReviewV1 {
    private const val PREF="report_review_v1"
    fun load(c:Context,key:String,original:List<TikTokReportExporter.Event>):ReviewStateV1{
        val raw=c.getSharedPreferences(PREF,0).getString(key,null)
        if(raw.isNullOrBlank()) return ReviewStateV1(events=original.mapIndexed{i,e->ReviewedEventV1(i,true,e.t0,e.t1)})
        return runCatching{
            val o=JSONObject(raw);val a=o.optJSONArray("events")?:JSONArray()
            ReviewStateV1(o.optBoolean("confirmed"),o.optLong("confirmedAt"),(0 until a.length()).map{i->
                val x=a.getJSONObject(i);ReviewedEventV1(x.optInt("index",i),x.optBoolean("valid",true),x.optString("t0"),x.optString("t1"),x.optString("note"))
            })
        }.getOrElse{ReviewStateV1(events=original.mapIndexed{i,e->ReviewedEventV1(i,true,e.t0,e.t1)})}
    }
    fun save(c:Context,key:String,s:ReviewStateV1){
        val o=JSONObject().put("confirmed",s.confirmed).put("confirmedAt",s.confirmedAt).put("events",JSONArray().apply{s.events.forEach{e->put(JSONObject().put("index",e.index).put("valid",e.valid).put("t0",e.t0).put("t1",e.t1).put("note",e.note))}})
        c.getSharedPreferences(PREF,0).edit().putString(key,o.toString()).apply()
    }
    fun offsetMs(clock:String,sessionStart:String):Long?{
        fun p(x:String)=runCatching{java.text.SimpleDateFormat("HH:mm:ss.SSS",java.util.Locale.US).parse(x)?.time}.getOrNull()
        val a=p(clock)?:return null;val b=p(sessionStart)?:return null
        var d=a-b;if(d<0)d+=24*60*60*1000L;return d
    }
}