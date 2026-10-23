package com.example.celltracker

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperienceAnalysisScreenV130(onBack:()->Unit) {
    val context=LocalContext.current
    val repo=remember(context){ExperienceRepositoryV130(context)}
    val scope=rememberCoroutineScope()
    var page by remember { mutableStateOf("Data") }
    var session by remember { mutableStateOf("Field_${SimpleDateFormat("yyyyMMdd",Locale.US).format(Date())}") }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())) }
    var scenario by remember { mutableStateOf("Field Test") }
    var operator by remember { mutableStateOf("Jazz") }
    var rat by remember { mutableStateOf("4G") }
    var role by remember { mutableStateOf("DUT1") }
    var rules by remember { mutableStateOf(repo.rules()) }
    var rows by remember { mutableStateOf(repo.rows()) }
    var overrides by remember { mutableStateOf(repo.overrides()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var manualMetric by remember { mutableStateOf(ExperienceMetrics.all.first().id) }
    var manualValue by remember { mutableStateOf("") }
    var manualAttempts by remember { mutableStateOf("20") }
    var lastExport by remember { mutableStateOf<android.net.Uri?>(null) }
    val decisions=remember(rows,rules,session,overrides){ExperienceAnalyzer.compare(rows,rules,session,overrides)}
    fun refresh(){rows=repo.rows();rules=repo.rules();overrides=repo.overrides()}
    fun runJob(block:()->String){
        if(busy)return
        busy=true
        scope.launch{
            message=try{withContext(Dispatchers.IO){block()}}catch(e:Exception){"Error: ${e.message.orEmpty()}"}
            busy=false;refresh()
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runJob{val result=repo.importTemplate(uri,session,date,scenario,operator,rat);"Imported ${result.first} new readings. ${result.second}"}
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title={Text("Experience Analysis")},navigationIcon={TextButton(onClick=onBack){Text("Back")}})
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("Data","Rules","Compare","Reports").forEach{tab->
                FilterChip(selected=page==tab,onClick={page=tab},label={Text(tab)},modifier=Modifier.weight(1f))
            }
        }
        if(message.isNotBlank()) Text(message,modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(page=="Data") {
                Text("1. Session / Matching Conditions",style=MaterialTheme.typography.titleMedium)
                OutlinedTextField(session,{session=it},label={Text("Session ID (required for comparison)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                val existingSessions=rows.map{it.session}.distinct().filter{it.isNotBlank()}
                if(existingSessions.isNotEmpty()) ExperienceChoice("Saved session",session,existingSessions){chosen->
                    session=chosen
                    rows.firstOrNull{it.session==chosen}?.let{r->date=r.date;scenario=r.scenario;operator=r.operator;rat=r.rat}
                }
                OutlinedTextField(date,{date=it},label={Text("Test date: YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(scenario,{scenario=it},label={Text("Scenario / Site")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(operator,{operator=it},label={Text("Operator")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(rat,{rat=it},label={Text("RAT")},singleLine=true,modifier=Modifier.weight(1f))
                }
                ExperienceChoice("Device role",role,ExperienceAnalyzer.roles){role=it}
                Card { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Import existing measurements",fontWeight=FontWeight.SemiBold)
                    Text("Reads this device's Ping, TikTok, YouTube, WhatsApp Image and Call reports, with source paths retained. Confirm role and RAT first.",style=MaterialTheme.typography.bodySmall)
                    Button(onClick={runJob{val count=repo.scanLocal(session,date,scenario,operator,rat,role);"Added $count local readings; existing IDs de-duplicated"}},enabled=!busy&&session.isNotBlank()) { Text("Load My Local Reports") }
                    OutlinedButton(onClick={importer.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","text/*","application/octet-stream"))},enabled=!busy&&session.isNotBlank()) { Text("Import Test Results Excel / CSV") }
                    Text("Excel input: Communication Experience Test Cases and Results V1.0 → Test Results. The Role column must identify DUT1, DUT2, REF1 or REF2; ambiguous DUT/REF rows are skipped.",style=MaterialTheme.typography.bodySmall)
                } }
                Card { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Add measurement manually",fontWeight=FontWeight.SemiBold)
                    ExperienceChoice("Metric",manualMetric,ExperienceMetrics.all.map{it.id}){manualMetric=it}
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(manualValue,{manualValue=it},label={Text("Value (${ExperienceMetrics.find(manualMetric)?.unit})")},singleLine=true,modifier=Modifier.weight(1f))
                        OutlinedTextField(manualAttempts,{manualAttempts=it},label={Text("Attempts")},singleLine=true,modifier=Modifier.weight(1f))
                    }
                    Button(enabled=!busy&&manualValue.toDoubleOrNull()!=null&&manualAttempts.toIntOrNull()!=null&&session.isNotBlank(),onClick={
                        runJob{repo.addManual(session,date,scenario,operator,rat,role,manualMetric,manualValue.toDouble(),manualAttempts.toInt());"Manual measurement saved with MANUAL provenance"}
                    }) { Text("Save Manual Result") }
                } }
                Text("Current session readings (${rows.count{it.session==session}})",style=MaterialTheme.typography.titleMedium)
                rows.filter{it.session==session}.takeLast(50).forEach{r->
                    Card { Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)){
                            Text("${r.role} · ${ExperienceMetrics.find(r.metricId)?.label ?: r.metricId} · ${"%.2f".format(Locale.US,r.value)}",style=MaterialTheme.typography.bodyMedium)
                            Text("${r.dataOrigin} · ${r.attempts} attempts · ${r.source.takeLast(70)}",style=MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick={repo.remove(r.id);refresh()}){Text("Delete")}
                    } }
                }
            }
            if(page=="Rules") {
                Text("2. Configurable PASS / OBSERVE / FAIL rules",style=MaterialTheme.typography.titleMedium)
                Text("Each metric has its own delta type and two thresholds. OFF means OBSERVE, not automatic PASS. Save before analysis; no fixed project threshold is assumed.",style=MaterialTheme.typography.bodySmall)
                rules.forEach{r->
                    val m=ExperienceMetrics.find(r.metricId)?:return@forEach
                    var observe by remember(r.metricId,rules){mutableStateOf(r.observeAt.toString())}
                    var fail by remember(r.metricId,rules){mutableStateOf(r.failAt.toString())}
                    var attempts by remember(r.metricId,rules){mutableStateOf(r.minAttempts.toString())}
                    Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)){
                                Text(m.label,fontWeight=FontWeight.SemiBold)
                                Text("${m.deltaUnit.name} · ${m.direction.name}",style=MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked=r.enabled,onCheckedChange={enabled->val updated=rules.map{if(it.metricId==r.metricId)it.copy(enabled=enabled) else it};repo.saveRules(updated);rules=repo.rules()})
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(observe,{observe=it},label={Text("OBSERVE >=")},singleLine=true,modifier=Modifier.weight(1f))
                            OutlinedTextField(fail,{fail=it},label={Text("FAIL >=")},singleLine=true,modifier=Modifier.weight(1f))
                            OutlinedTextField(attempts,{attempts=it},label={Text("Min N")},singleLine=true,modifier=Modifier.weight(0.8f))
                        }
                        OutlinedButton(onClick={
                            val o=observe.toDoubleOrNull();val f=fail.toDoubleOrNull();val n=attempts.toIntOrNull()
                            if(o==null||f==null||n==null||o<0||f<o||n<1||!o.isFinite()||!f.isFinite())message="Invalid rule: 0 <= OBSERVE <= FAIL, samples >= 1"
                            else {val updated=rules.map{if(it.metricId==r.metricId)it.copy(observeAt=o,failAt=f,minAttempts=n) else it};repo.saveRules(updated);rules=repo.rules();message="Saved ${m.label}"}
                        }){Text("Save Thresholds")}
                    } }
                }
            }
            if(page=="Compare") {
                Text("3. DUT vs REF comparison",style=MaterialTheme.typography.titleMedium)
                Text("REF value = mean of available, matching REF1/REF2. Results are N/A when evidence is absent or comparison conditions differ. Automatic verdicts are preliminary.",style=MaterialTheme.typography.bodySmall)
                val visible=decisions.filter{it.dutValue!=null||it.refValue!=null}
                Text("${visible.size} metric decisions · ${repo.ruleVersion()}",style=MaterialTheme.typography.bodySmall)
                visible.forEach{d->
                    var reason by remember(d.metricId,d.dutRole){mutableStateOf(d.overrideReason)}
                    var bug by remember(d.metricId,d.dutRole){mutableStateOf(d.bugId)}
                    var reviewed by remember(d.metricId,d.dutRole){mutableStateOf(d.finalVerdict.name)}
                    Card { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text("${d.dutRole} · ${ExperienceMetrics.find(d.metricId)?.label}",fontWeight=FontWeight.SemiBold)
                        Text("DUT ${d.dutValue?.let{"%.2f".format(Locale.US,it)}?:"N/A"}  ·  REF ${d.refValue?.let{"%.2f".format(Locale.US,it)}?:"N/A"}  ·  Gap ${d.difference?.let{"%.2f".format(Locale.US,it)}?:"N/A"}")
                        Text("AUTO ${d.automated.name}  →  FINAL ${d.finalVerdict.name}",style=MaterialTheme.typography.bodyMedium)
                        Text(d.reason,style=MaterialTheme.typography.bodySmall)
                        ExperienceChoice("Manual assessment",reviewed,ExperienceVerdict.values().map{it.name}){reviewed=it}
                        OutlinedTextField(reason,{reason=it},label={Text("Override reason (required)")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(bug,{bug=it},label={Text("Bug ID (optional)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        OutlinedButton(onClick={
                            if(reason.isBlank()) message="A reason is required to override an automatic verdict"
                            else{repo.saveOverride("$session|${d.metricId}|${d.dutRole}",ExperienceVerdict.valueOf(reviewed),reason,bug);overrides=repo.overrides();message="Manual review saved; original AUTO result preserved"}
                        }) {Text("Save Manual Review")}
                    } }
                }
            }
            if(page=="Reports") {
                Text("4. Template-driven reporting",style=MaterialTheme.typography.titleMedium)
                Text("Outputs use only loaded/imported/manual-labeled results. The daily layout follows the 8 sections in the Communication Experience Daily Report template. The weekly HTML follows W42's five-section structure.",style=MaterialTheme.typography.bodySmall)
                listOf("author" to "Prepared by","reviewer" to "Reviewed by","city" to "Market / City",
                    "owner" to "Bug owner/team","summary" to "Executive summary","actions" to "Next test day actions","group" to "Group update text",
                    "weekly_findings" to "Weekly findings / opportunities","weekly_risks" to "Weekly bug / risk progress",
                    "weekly_knowledge" to "Team capability and learning","weekly_plan" to "Next week plan").forEach{(id,label)->
                    var content by remember(id){mutableStateOf(repo.note(id))}
                    OutlinedTextField(content,{content=it;repo.saveNote(id,it)},label={Text(label)},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=5)
                }
                Button(onClick={runJob{
                    val full=ExperienceReportExportV130.exportResults(context,session,rows,decisions,rules,date,repo.ruleVersion())
                    val sessionRows=rows.count{it.session==session};val comparisons=decisions.count{it.dutValue!=null||it.refValue!=null}
                    if(sessionRows<=100 && comparisons<=100) {
                        val template=ExperienceOriginalTemplateV130.export(context,session,rows,decisions,date)
                        lastExport=template
                        "Exported template workbook + 9-sheet analysis workbook to Downloads / CellTracker / $date / Analysis"
                    } else {lastExport=full;"Exported complete 9-sheet analysis workbook. Original template capped at 100 rows; no entries dropped."}
                }},enabled=!busy&&rows.any{it.session==session}){Text("Export Test Result Excel")}
                Button(onClick={runJob{
                    val notes=listOf("summary","actions","group","author","reviewer","city","owner").associateWith{repo.note(it)}
                    ExperienceReportExportV130.exportDaily(context,session,date,rows,decisions,notes,repo.ruleVersion())
                    val uri=ExperienceDailyDocxV130.export(context,session,date,rows,decisions,notes,repo.ruleVersion())
                    lastExport=uri;"Generated daily Word DOCX (from original template) + HTML preview"
                }},enabled=!busy&&rows.any{it.session==session}){Text("Generate Daily Report")}
                Button(onClick={runJob{
                    val notes=listOf("weekly_findings","weekly_risks","weekly_knowledge","weekly_plan").associateWith{repo.note(it)}
                    val uri=ExperienceReportExportV130.exportWeekly(context,date,rows,rules,notes,repo.ruleVersion())
                    lastExport=uri;"Weekly HTML exported for the 7 days ending $date"
                }},enabled=!busy&&rows.isNotEmpty()){Text("Generate Weekly Report")}
                if(lastExport!=null)OutlinedButton(onClick={
                    val uri=lastExport?:return@OutlinedButton
                    val intent=Intent(Intent.ACTION_SEND).apply{type="*/*";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                    runCatching{context.startActivity(Intent.createChooser(intent,"Share report"))}.onFailure{Toast.makeText(context,it.message,Toast.LENGTH_SHORT).show()}
                }){Text("Share Last Export")}
                Text("Note: multi-agent automatic synchronization remains pending. Daily uses the supplied Word template; weekly currently exports editable HTML based on W42 sections.",style=MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ExperienceChoice(label:String,value:String,options:List<String>,onSelected:(String)->Unit){
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true}){Text("$label: $value ▾",maxLines=1)}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){
            options.forEach{o->DropdownMenuItem(text={Text(o)},onClick={onSelected(o);expanded=false})}
        }
    }
}
