package com.example.celltracker

import android.content.Context
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.Locale

/** Preserves the user's supplied workbook styles, test cases, formulas, formatting and four tabs. */
object ExperienceOriginalTemplateV130 {
    private const val ASSET="Communication_Experience_Test_Cases_and_Results_V1.0_EN.xlsx"
    private val ids=mapOf("SPEEDTEST_DL_MBPS" to "DL-04", "SPEEDTEST_UL_MBPS" to "UL-03", "PING_AVG_MS" to "APP-PING","PING_SUCCESS" to "APP-PING","MFT_PING_SUCCESS" to "SIG-01",
        "MFT_PING_AVG_MS" to "SIG-01","MFT_SHORT_VIDEO_AVG_MS" to "DL-01","TIKTOK_STALLS" to "DL-02",
        "TIKTOK_STALL_SEC" to "DL-02","YOUTUBE_AVG_MS" to "DL-03","TIKTOK_UPLOAD_AVG_MS" to "UL-01",
        "TIKTOK_UPLOAD_MAX_MS" to "UL-01","WHATSAPP_SEND_AVG_MS" to "UL-02","CALL_SUCCESS" to "CALL-GENERIC",
        "CALL_SETUP_AVG_MS" to "CALL-GENERIC","VOLTE_SUCCESS" to "CALL-01",
        "WHATSAPP_CALL_SUCCESS" to "CALL-02","VOLTE_LONG_SUCCESS" to "CALL-03","RECOVERY_MS" to "COV-01")
    private fun xml(v:String)=v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    private fun cellName(n:Int):String{var x=n+1;var a="";while(x>0){a=('A'.code+(x-1)%26).toChar()+a;x=(x-1)/26};return a}
    private fun filled(sheet:String, data:List<List<String>>,numberCols:Set<Int>):String {
        var result=sheet
        data.take(100).forEachIndexed{index,row->val line=index+2
            row.forEachIndexed cell@{col,value->
                if(value.isBlank())return@cell
                val loc="${cellName(col)}$line"
                val pattern=Regex("<x:c r=\"${Regex.escape(loc)}\"[^>]*?/>",RegexOption.DOT_MATCHES_ALL)
                val prior=pattern.find(result)?.value ?: return@cell
                val style=Regex("\\bs=\"(\\d+)\"").find(prior)?.groupValues?.get(1).orEmpty()
                val styleAttr=if(style.isBlank())"" else " s=\"$style\""
                val valueType=if(col in numberCols&&value.toDoubleOrNull()!=null) "<x:v>$value</x:v>" else "<x:is><x:t xml:space=\"preserve\">${xml(value)}</x:t></x:is>"
                val t=if(col in numberCols&&value.toDoubleOrNull()!=null)"" else " t=\"inlineStr\""
                result=result.replaceRange(result.indexOf(prior),result.indexOf(prior)+prior.length,
                    "<x:c r=\"$loc\"$styleAttr$t>$valueType</x:c>")
            }
        }
        return result
    }
    fun export(context:Context,session:String,all:List<ExperienceObservation>,decisions:List<ExperienceDecision>,date:String):Uri {
        val rows=all.filter{it.session==session}
        require(rows.size<=100) { "The supplied Test Results template contains 100 data rows; choose a smaller session or use the full analysis workbook." }
        val tests=rows.map{r->
            val m=ExperienceMetrics.find(r.metricId)
            val d=decisions.firstOrNull{it.metricId==r.metricId&&it.dutRole==r.role}
            val numbers=Array(23){""}
            numbers[0]=r.date;numbers[1]=r.scenario;numbers[3]=r.operator;numbers[4]=r.rat
            numbers[5]=ids[r.metricId].orEmpty();numbers[6]=r.deviceModel.ifBlank{r.role};numbers[7]=r.role
            numbers[10]=r.attempts.toString();numbers[16]=r.rsrp
            when (r.metricId) {
                "MFT_PING_AVG_MS","PING_AVG_MS","CALL_SETUP_AVG_MS"->numbers[13]=r.value.toString()
                "TIKTOK_STALLS"->numbers[17]=r.value.toString()
                "TIKTOK_STALL_SEC"->numbers[18]=r.value.toString()
                "SPEEDTEST_DL_MBPS", "SPEEDTEST_UL_MBPS" -> { /* Speed in Mbps is preserved in Notes, not falsely mapped to latency columns. */ }
                "PING_SUCCESS","MFT_PING_SUCCESS","CALL_SUCCESS","VOLTE_SUCCESS","WHATSAPP_CALL_SUCCESS","VOLTE_LONG_SUCCESS"->{}
                // Success/failure counts cannot be reconstructed accurately from a rounded rate;
                // preserve the measured rate in Notes instead of inventing raw successes.
                else->{numbers[14]=(r.value/if(m?.unit=="ms")1000.0 else 1.0).toString()}
            }
            numbers[19]="${m?.label.orEmpty()} = ${r.value} ${m?.unit.orEmpty()} | ${r.dataOrigin} | ${r.id}"
            numbers[20]=d?.finalVerdict?.name.orEmpty()
            numbers[21]=d?.bugId.orEmpty();numbers[22]=r.source
            numbers.toList()
        }
        val matches=decisions.filter{it.dutValue!=null||it.refValue!=null}.take(100).map { d->
            val r=rows.firstOrNull{it.metricId==d.metricId&&it.role==d.dutRole}
            val a=Array(16){""}
            a[0]=r?.date.orEmpty();a[1]=r?.scenario.orEmpty();a[2]=r?.operator.orEmpty();a[3]=r?.rat.orEmpty()
            a[4]=ids[d.metricId].orEmpty();a[5]="${ExperienceMetrics.find(d.metricId)?.label} (${ExperienceMetrics.find(d.metricId)?.unit})"
            a[6]=r?.deviceModel?.ifBlank{d.dutRole} ?: d.dutRole;a[7]=d.dutValue?.toString().orEmpty();a[8]="Comparable REF Mean";a[9]=d.refValue?.toString().orEmpty()
            a[12]=if(ExperienceMetrics.find(d.metricId)?.direction==ExperienceDirection.LOWER_IS_BETTER)"Lower Better" else "Higher Better"
            a[13]=d.finalVerdict.name;a[14]=d.bugId;a[15]="${d.reason}; Evidence: ${d.evidenceIds.joinToString(";")}; ${d.overrideReason}"
            a.toList()
        }
        require(matches.size<=100) { "The supplied DUT vs REF template contains 100 comparison rows; use the full analysis workbook." }
        val original=context.assets.open(ASSET).use{it.readBytes()}
        val out=ByteArrayOutputStream()
        ZipInputStream(original.inputStream()).use{input->ZipOutputStream(out).use{zip->while(true){val entry=input.nextEntry?:break
            val bytes=input.readBytes()
            val result=when(entry.name){
                "xl/worksheets/sheet3.xml"->filled(bytes.toString(Charsets.UTF_8),tests,setOf(10,11,12,13,14,15,16,17,18)).replace("\"DUT,REF\"","\"DUT1,DUT2,REF1,REF2\"").replace("\"PASS,WATCH,FAIL,INVALID\"","\"PASS,OBSERVE,FAIL,N/A\"")
                "xl/worksheets/sheet4.xml"->filled(bytes.toString(Charsets.UTF_8),matches,setOf(7,9)).replace("\"PASS,WATCH,FAIL,INVALID\"","\"PASS,OBSERVE,FAIL,N/A\"")
                else->null
            }
            zip.putNextEntry(ZipEntry(entry.name));zip.write(result?.toByteArray(Charsets.UTF_8)?:bytes);zip.closeEntry();input.closeEntry()
        }}}
        val name="Communication_Experience_Test_Result_${date}_${session.take(20)}_Original_Template.xlsx"
        val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/$date/Analysis")}
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create template workbook")
        context.contentResolver.openOutputStream(uri)?.use{it.write(out.toByteArray())}?:error("Cannot write template workbook")
        return uri
    }
}
