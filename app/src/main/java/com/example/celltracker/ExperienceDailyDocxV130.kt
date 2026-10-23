package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.Document
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Populate the actual supplied English daily Word template, preserving its styles and other DOCX assets. */
object ExperienceDailyDocxV130 {
    private const val asset="07_Communication_Experience_Daily_Report_Template_V1.0_EN.docx"
    private const val NS="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private fun textNodes(parent:Node):List<Node>{val nodes=(parent as Element).getElementsByTagNameNS(NS,"t")
        return (0 until nodes.length).map{nodes.item(it)}}
    private fun replaceText(parent:Node,text:String,doc:Document) {
        val nodes=textNodes(parent)
        if(nodes.isNotEmpty()) {nodes[0].textContent=text;nodes.drop(1).forEach{it.textContent=""};return}
        val p=doc.createElementNS(NS,"w:p");val r=doc.createElementNS(NS,"w:r");val t=doc.createElementNS(NS,"w:t")
        t.textContent=text;r.appendChild(t);p.appendChild(r);parent.appendChild(p)
    }
    private fun direct(parent:Node,name:String):List<Node>{val nodes=parent.childNodes
        return (0 until nodes.length).map{nodes.item(it)}.filter{it.localName==name&&it.namespaceURI==NS}}
    private fun table(doc:Document,index:Int):Node=doc.getElementsByTagNameNS(NS,"tbl").item(index)?:error("Daily DOCX table missing: $index")
    private fun fillTable(doc:Document,index:Int,values:List<List<String>>) {
        val target=table(doc,index)
        val existing=direct(target,"tr")
        val proto=(existing.getOrNull(1)?:existing.firstOrNull())?.cloneNode(true)?:error("No template row")
        existing.drop(1).forEach{target.removeChild(it)}
        values.forEach{row->val newRow=proto.cloneNode(true)
            val cells=direct(newRow,"tc")
            cells.forEachIndexed{i,c->replaceText(c,row.getOrNull(i).orEmpty(),doc)}
            target.appendChild(newRow)
        }
    }
    private fun updateDocument(original:ByteArray,session:String,date:String,rows:List<ExperienceObservation>,decisions:List<ExperienceDecision>,notes:Map<String,String>,version:String):ByteArray {
        val builder=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true
            runCatching{setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
            runCatching{setFeature("http://xml.org/sax/features/external-general-entities",false)}
        }.newDocumentBuilder()
        val doc=builder.parse(original.inputStream())
        val current=rows.filter{it.session==session}
        val visible=decisions.filter{it.dutValue!=null||it.refValue!=null}
        val status=ExperienceVerdict.values().associateWith{v->visible.count{it.finalVerdict==v}}
        val op=current.map{it.operator}.distinct().joinToString(", ")
        val scenario=current.map{it.scenario}.distinct().joinToString(", ")
        fillTable(doc,0,listOf(listOf(current.map{it.source}.distinct().size.toString(),
            "${status[ExperienceVerdict.PASS]}","${status[ExperienceVerdict.FAIL]}",
            "${status[ExperienceVerdict.OBSERVE]}","${status[ExperienceVerdict.NA]}")))
        fillTable(doc,1,listOf(
            listOf("Date / Time Window",date),listOf("Test Scenario / Site",scenario),
            listOf("Operator / SIM Setup",op),listOf("Planned / Actual RAT",current.map{it.rat}.distinct().joinToString(", ")),
            listOf("DUT 1 / DUT 2",current.filter{it.role.startsWith("DUT")}.map{it.role}.distinct().joinToString(", ")),
            listOf("REF 1 / REF 2",current.filter{it.role.startsWith("REF")}.map{it.role}.distinct().joinToString(", ")),
            listOf("Apps / Tool versions","CellTracker 1.3.0; original report source linked"),
            listOf("Session / Report ID","$session · $version")))
        fun value(m:String,role:String):String=current.filter{it.metricId==m&&it.role==role}.maxByOrNull{it.recordedAt}?.value?.let{"%.3f".format(Locale.US,it)}?:"N/A"
        val metrics=visible.map{it.metricId}.distinct()
        fillTable(doc,2,metrics.map{m->val item=ExperienceMetrics.find(m)
            val verdict=visible.filter{it.metricId==m}.joinToString(" / "){"${it.dutRole}: ${it.finalVerdict}"}
            listOf("${item?.label?:m} (${item?.unit.orEmpty()})",value(m,"DUT1"),value(m,"DUT2"),value(m,"REF1"),value(m,"REF2"),verdict)
        })
        fillTable(doc,3,visible.filter{it.finalVerdict!=ExperienceVerdict.PASS}.map{d->
            listOf("${d.metricId} / ${d.finalVerdict}","DUT=${d.dutValue}; REF=${d.refValue}; gap=${d.difference}",
                d.reason+"; "+ExperienceNetworkHintsV130.describe(d,current))
        })
        fillTable(doc,4,visible.filter{it.finalVerdict==ExperienceVerdict.FAIL||it.bugId.isNotBlank()}.map{d->
            listOf(d.bugId.ifBlank{"Pending"},ExperienceMetrics.find(d.metricId)?.label.orEmpty(),notes["owner"].orEmpty(),
                "${d.finalVerdict}: ${d.overrideReason.ifBlank{d.reason}}")
        })
        fillTable(doc,5,current.map{r->listOf(r.dataOrigin,r.source)}.distinct().take(100))
        val paragraphs=doc.getElementsByTagNameNS(NS,"p")
        val summary=notes["summary"].orEmpty().ifBlank{"${visible.size} DUT metrics compared using $version. Verify evidence before reporting an issue."}
        val group=notes["group"].orEmpty().ifBlank{"$date · $scenario · $op · ${visible.size} DUT comparisons; ${status[ExperienceVerdict.FAIL]} FAIL, ${status[ExperienceVerdict.OBSERVE]} OBSERVE. Follow-up pending."}
        val removeBlankActions=mutableListOf<Node>()
        for(i in 0 until paragraphs.length){val p=paragraphs.item(i);val old=textNodes(p).joinToString(""){it.textContent.orEmpty()}
            when {
                old.contains("Communication Experience Daily Report")&&old.contains("Reusable") -> replaceText(p,"Communication Experience Daily Report — $date",doc)
                old.contains("Editable field report") -> replaceText(p,"Generated field report | Original records and manual provenance retained",doc)
                old.startsWith("Version 1.0") -> replaceText(p,"Template v1.0 | Evaluation rules $version | Language: English",doc)
                old.startsWith("Template use:") -> replaceText(p,"Use only comparable reports and verified/manual-labeled values; missing results remain N/A.",doc)
                old.startsWith("Example phrasing") -> replaceText(p,"Automatic judgments are preliminary and must not be represented as confirmed root cause.",doc)
                old.contains("Comparability / sample note:") -> replaceText(p,"Comparability: Matching session, scenario, date, RAT and operator required. REF = mean of comparable reference devices.",doc)
                old.startsWith("Date: [YYYY-MM-DD]")->replaceText(p,"Date: $date     Market / City: ${notes["city"].orEmpty()}     Scenario: $scenario",doc)
                old.startsWith("Summary: [One") ->replaceText(p,"Summary: $summary",doc)
                old.contains("[Action 1") ->replaceText(p,notes["actions"].orEmpty().ifBlank{"Review results with original reports; file and track confirmed DUT gaps."},doc)
                old.contains("[Action 2") ->removeBlankActions+=p
                old.contains("[Engineering dependency") ->removeBlankActions+=p
                old.contains("Today we completed [") ->replaceText(p,group,doc)
                old.startsWith("Prepared by:")->replaceText(p,"Prepared by: ${notes["author"].orEmpty()}     Reviewed by: ${notes["reviewer"].orEmpty()}",doc)
            }
        }
        removeBlankActions.forEach{p->p.parentNode?.removeChild(p)}
        val out=ByteArrayOutputStream();TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc),StreamResult(out))
        return out.toByteArray()
    }
    fun export(context:Context,session:String,date:String,all:List<ExperienceObservation>,decisions:List<ExperienceDecision>,notes:Map<String,String>,ruleVersion:String):Uri {
        val original=context.assets.open(asset).use{it.readBytes()}
        val out=ByteArrayOutputStream()
        ZipInputStream(original.inputStream()).use{input->ZipOutputStream(out).use{zip->while(true){val e=input.nextEntry?:break
            val bytes=input.readBytes();val converted=if(e.name=="word/document.xml")updateDocument(bytes,session,date,all,decisions,notes,ruleVersion)else bytes
            zip.putNextEntry(ZipEntry(e.name));zip.write(converted);zip.closeEntry();input.closeEntry()
        }}}
        val name="Communication_Experience_Daily_${date}_${session.take(20)}.docx"
        val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/$date/Analysis")}
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create Word report")
        context.contentResolver.openOutputStream(uri)?.use{it.write(out.toByteArray())}?:error("Cannot write Word report")
        return uri
    }
}
