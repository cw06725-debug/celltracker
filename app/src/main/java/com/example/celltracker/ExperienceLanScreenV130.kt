package com.example.celltracker

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private data class LanPeerConfig(val role:String,val address:String="",val key:String="")

private fun loadLanPeers(c:Context):List<LanPeerConfig>{
    val raw=c.getSharedPreferences("experience_lan_v130",0).getString("peers","").orEmpty()
    val existing=runCatching{JSONArray(raw)}.getOrNull()
    return (0..2).map {i-> val o=existing?.optJSONObject(i)
        LanPeerConfig(o?.optString("role")?.takeIf{it in ExperienceAnalyzer.roles}?:listOf("DUT2","REF1","REF2")[i],
            o?.optString("ip").orEmpty()) }
}
private fun saveLanPeers(c:Context,peers:List<LanPeerConfig>){
    val a=JSONArray();peers.forEach{a.put(JSONObject().apply{put("role",it.role);put("ip",it.address)})}
    c.getSharedPreferences("experience_lan_v130",0).edit().putString("peers",a.toString()).apply()
}

@Composable
internal fun ExperienceLanScreenV130(
    server:ExperienceLanShareV130,
    repository:ExperienceRepositoryV130,
    session:String,date:String,scenario:String,operator:String,rat:String,
    role:String,onRoleChange:(String)->Unit,
    busy:Boolean,onJob:((()->String)->Unit),onMessage:(String)->Unit
) {
    val context=LocalContext.current
    var peers by remember { mutableStateOf(loadLanPeers(context)) }
    var revision by remember { mutableIntStateOf(0) }
    val isSynced = remember(revision) { server.isSynced }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Post-test Wi-Fi Report Collection",style=MaterialTheme.typography.titleMedium)
        Text("Four phones test independently. Only AFTER finishing tests, join one trusted Wi-Fi network/hotspot and use this page to collect results. Bluetooth Call Setup is unchanged.",style=MaterialTheme.typography.bodySmall)
        Card { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("On each Agent: share completed reports",fontWeight=FontWeight.SemiBold)
            Text("Selected Session: $session | Date: $date",style=MaterialTheme.typography.bodySmall)
            LanRoleChoice(role,onRoleChange)
            Text("Use the same Session ID on all devices. Choose this phone's real DUT/REF role and use matching test date, scenario and operator.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onJob {
                    val b=server.start(session,date,scenario,operator,rat,role)
                    "Sharing ${b.rowCount} measurements and ${b.files} original files. " +
                        "${b.skipped} source files unavailable/over size limit. Keep this page open until synced."
                };revision++},enabled=!busy&&session.isNotBlank()) { Text(if(server.isSharing)"Refresh Share" else "Start Sharing") }
                OutlinedButton(onClick={server.stop();revision++;onMessage("Wi-Fi report sharing stopped")},enabled=server.isSharing){Text("Stop")}
            }
            if(server.isSharing){
                Text("Agent address: ${server.addressText}",fontWeight=FontWeight.Medium)
                Text("Port: ${ExperienceLanV130.PORT}   |   Pairing code: ${server.accessCode}",fontWeight=FontWeight.SemiBold)
                Text("Report ID: ${server.report?.id?.take(16)}…  ·  ${server.report?.rowCount} readings  ·  ${server.report?.files} files",
                    style=MaterialTheme.typography.bodySmall)
                Text("Sync status: ${if(isSynced)"Controller confirmed Synced" else "Awaiting Controller acknowledgement"}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={revision++}){Text("Refresh sync status")}
            } else Text("Sharing: Off. Nothing is exposed on the local network.",style=MaterialTheme.typography.bodySmall)
        } }
        Card {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("On Controller: collect from 3 Agents",fontWeight=FontWeight.SemiBold)
            Text("Controller also loads its own reports in Data. Enter each Agent's local IP address and current pairing code, then sync one-by-one or all three.",style=MaterialTheme.typography.bodySmall)
            peers.forEachIndexed {i,p->
                HorizontalDivider()
                Text("Agent ${i+1}",fontWeight=FontWeight.SemiBold)
                LanRoleChoice(p.role){v->
                    peers=peers.toMutableList().also{it[i]=it[i].copy(role=v)};saveLanPeers(context,peers)
                }
                OutlinedTextField(p.address,{v->
                    peers=peers.toMutableList().also{it[i]=it[i].copy(address=v)};saveLanPeers(context,peers)
                },label={Text("Wi-Fi IPv4 address")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(p.key,{v->
                    peers=peers.toMutableList().also{it[i]=it[i].copy(key=v.uppercase().filterNot(Char::isWhitespace).take(12))}
                },label={Text("12-character Agent pairing code")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedButton(enabled=!busy&&p.address.isNotBlank()&&p.key.length==12&&session.isNotBlank(),onClick={
                    onJob {ExperienceLanV130.fetch(context,repository,p.address,p.key,session,p.role)}
                }){Text("Collect ${p.role} Report")}
            }
            val complete=peers.filter{it.address.isNotBlank()&&it.key.length==12}
            Button(enabled=!busy&&complete.isNotEmpty()&&complete.map{it.role}.distinct().size==complete.size&&
                role !in complete.map{it.role},onClick={
                val selected=complete.toList()
                onJob {
                    selected.joinToString("\n") {peer->
                        "${peer.role}: "+runCatching{ExperienceLanV130.fetch(context,repository,peer.address,peer.key,session,peer.role)}
                            .getOrElse{"Failed: ${it.message}"}
                    }
                }
            }){Text("Collect All Configured Agents")}
            Button(enabled=!busy&&complete.isNotEmpty()&&complete.map{it.role}.distinct().size==complete.size&&
                role !in complete.map{it.role}&&session.isNotBlank(),onClick={
                val selected=complete.toList()
                onJob {
                    val summary=mutableListOf<String>()
                    // Controller owns one of the test devices; its local data is collected only now,
                    // AFTER the user has finished testing.
                    runCatching{repository.scanLocal(session,date,scenario,operator,rat,role)}
                        .onFailure{summary.add("Controller local scan: ${it.message}")}
                    selected.forEach{peer->summary.add("${peer.role}: "+runCatching{
                        ExperienceLanV130.fetch(context,repository,peer.address,peer.key,session,peer.role)
                    }.getOrElse{"Not synced: ${it.message}"})}
                    val rows=repository.rows()
                    val own=rows.filter{it.session==session}
                    require(own.isNotEmpty()){ "No actual report data available; no report generated" }
                    val rules=repository.rules()
                    val decisions=ExperienceAnalyzer.compare(rows,rules,session,repository.overrides())
                    ExperienceReportExportV130.exportResults(context,session,rows,decisions,rules,date,repository.ruleVersion())
                    if(own.size<=100 && decisions.count{it.dutValue!=null||it.refValue!=null}<=100)
                        ExperienceOriginalTemplateV130.export(context,session,rows,decisions,date)
                    val notes=listOf("summary","actions","group","author","reviewer","city","owner")
                        .associateWith{repository.note(it)}
                    ExperienceReportExportV130.exportDaily(context,session,date,rows,decisions,notes,repository.ruleVersion())
                    ExperienceDailyDocxV130.export(context,session,date,rows,decisions,notes,repository.ruleVersion())
                    val weeklyNotes=listOf("weekly_findings","weekly_risks","weekly_knowledge","weekly_plan")
                        .associateWith{repository.note(it)}
                    ExperienceReportExportV130.exportWeekly(context,date,rows,rules,weeklyNotes,repository.ruleVersion())
                    summary.joinToString("\n") + "\nGenerated Excel, Daily Word/HTML and Weekly HTML from ${own.size} session readings. " +
                        if(summary.any{it.contains("Not synced")})"Partial report: retry missing Agents before final approval." else "Review rules and verdicts before final approval."
                }
            }){Text("Collect + Analyze + Generate Reports")}
            Text("Duplicate Report IDs are deduplicated; each Agent is marked Synced only after the Controller stores a verified archive. Failed Agents can be retried without losing their test data.",style=MaterialTheme.typography.bodySmall)
        } }
        Text("Data privacy: use a trusted, isolated Wi-Fi/hotspot only. This initial LAN protocol has a temporary access code and checksums but not end-to-end encryption; do not use on public Wi-Fi. Leave Agent sharing off outside report collection.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LanRoleChoice(role:String,onChange:(String)->Unit){
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Device role",style=MaterialTheme.typography.bodySmall)
        Box {OutlinedButton(onClick={expanded=true}){Text("$role ▾")}
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                ExperienceAnalyzer.roles.forEach{r->DropdownMenuItem(text={Text(r)},onClick={onChange(r);expanded=false})}
            }
        }
    }
}
