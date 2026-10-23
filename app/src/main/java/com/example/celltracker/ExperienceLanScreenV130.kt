package com.example.celltracker

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
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
    p2p:ExperienceP2pV130,
    repository:ExperienceRepositoryV130,
    session:String,date:String,scenario:String,operator:String,rat:String,
    role:String,onRoleChange:(String)->Unit,
    busy:Boolean,onJob:((()->String)->Unit),onMessage:(String)->Unit
) {
    val context=LocalContext.current
    var peers by remember { mutableStateOf(loadLanPeers(context)) }
    var revision by remember { mutableIntStateOf(0) }
    var showManual by remember { mutableStateOf(false) }
    val isSynced = remember(revision) { server.isSynced }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Post-test Wi-Fi Report Collection",style=MaterialTheme.typography.titleMedium)
        Text("After testing, one phone creates a CellTracker Wi-Fi Direct group. Agents find ONLY advertised CellTracker Controllers, connect, and share reports. Bluetooth Call Setup is unchanged.",style=MaterialTheme.typography.bodySmall)
        ExperienceP2pPanelV130(p2p,server,repository,session,date,scenario,operator,rat,role,onRoleChange,busy,onJob,onMessage)
        OutlinedButton(onClick={showManual=!showManual},modifier=Modifier.fillMaxWidth()){
            Text(if(showManual) "Hide manual LAN / hotspot fallback" else "Manual LAN / hotspot fallback (IP + pairing code)")
        }
        if(showManual) {
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
            }){Text("Collect + Generate (Full Session)")}
            Text("For a filtered daily analysis, use Collect All first, then Data > select dates, test items and exact reports > Apply Selected Reports > Compare / Reports. Full Session export above bypasses the Data selection.",style=MaterialTheme.typography.bodySmall)
            Text("Duplicate Report IDs are deduplicated; each Agent is marked Synced only after the Controller stores a verified archive. Failed Agents can be retried without losing their test data.",style=MaterialTheme.typography.bodySmall)
        } }
        }
        Text("Wi-Fi Direct connects nearby devices using an app-specific service. Android may request permission/connection approval. Reports still use the current non-TLS local transfer protocol; work only with trusted devices and turn sharing off afterward.",style=MaterialTheme.typography.bodySmall)
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

/** Wi-Fi Direct primary path: Controller creates a group; Agents discover our DNS-SD service only. */
@Composable
private fun ExperienceP2pPanelV130(
    p2p:ExperienceP2pV130,
    server:ExperienceLanShareV130,
    repository:ExperienceRepositoryV130,
    session:String,date:String,scenario:String,operator:String,rat:String,
    role:String,onRoleChange:(String)->Unit,
    busy:Boolean,onJob:((()->String)->Unit),onMessage:(String)->Unit
) {
    val context=LocalContext.current
    var mode by remember { mutableStateOf("Controller") }
    var tick by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf("") }
    var autoShareAttempted by remember { mutableStateOf("") }
    // Refresh only this small status panel while it is visible; no global AppState polling.
    LaunchedEffect(p2p) { while(true){ delay(2500); tick++ } }
    val observed=tick
    // The user's tap on Connect is the explicit post-test action. Prepare and announce
    // automatically on connection; no second mandatory Agent button.
    val connectionFingerprint=if(mode=="Agent" && p2p.connected && !p2p.isOwner)
        "${p2p.groupOwnerIp}|$session|$date|$role" else ""
    LaunchedEffect(connectionFingerprint) {
        if(connectionFingerprint.isNotBlank() && connectionFingerprint!=autoShareAttempted && !busy){
            autoShareAttempted=connectionFingerprint
            onJob {
                // A few devices report P2P CONNECTED before their interface obtains IPv4.
                repeat(6) { attempt ->
                    if(ExperienceLanV130.p2pAddresses().isEmpty() && attempt<5)Thread.sleep(700)
                }
                require(ExperienceLanV130.p2pAddresses().isNotEmpty()) {
                    "Wi-Fi Direct IPv4 address is not ready. Reconnect and retry."
                }
                val b=server.start(session,date,scenario,operator,rat,role)
                val announced=runCatching{
                    var response=""
                    repeat(4){retry->
                        if(response.isEmpty()){
                            response=runCatching{p2p.announce(role,session,server.accessCode)}.getOrElse{
                                if(retry<3)Thread.sleep(800)
                                else throw it
                                ""
                            }
                        }
                    }
                    response
                }.getOrElse{throw IllegalStateException("Share ready, Controller registration failed: ${it.message}")}
                "Automatically shared ${b.rowCount} measurements / ${b.files} original files. $announced"
            }
        }
    }
    val permissionNames=if(Build.VERSION.SDK_INT>=33)
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES,Manifest.permission.ACCESS_FINE_LOCATION)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    fun permitted()=permissionNames.all{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED}
    fun start(requested:String){
        runCatching { if(requested=="controller")p2p.startController(session) else p2p.findControllers() }
            .onFailure { onMessage("Wi-Fi Direct: ${it.message}") }
        tick++
    }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){grants->
        if(grants.values.all{it})start(pending)
        else onMessage("Grant Nearby Wi-Fi and Location access, then try again. Android may also require Location services enabled.")
        pending=""
    }
    fun requestStart(which:String){
        if(permitted())start(which)
        else {pending=which;permissionLauncher.launch(permissionNames)}
    }
    Card { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Discover & Connect — CellTracker only",fontWeight=FontWeight.SemiBold)
        if(!p2p.available){Text("Wi-Fi Direct not available: use manual LAN below.",color=MaterialTheme.colorScheme.error)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Controller","Agent").forEach{choice->
                FilterChip(selected=mode==choice,onClick={
                    if(mode!=choice){if(mode=="Agent")server.stop();p2p.stop();mode=choice;tick++}
                },label={Text(choice)},colors=FilterChipDefaults.filterChipColors(
                    selectedContainerColor=MaterialTheme.colorScheme.primary,
                    selectedLabelColor=MaterialTheme.colorScheme.onPrimary))
            }
        }
        Text("My role",style=MaterialTheme.typography.labelMedium)
        LanRoleChoice(role,onRoleChange)
        Text("Connection: ${p2p.status}",style=MaterialTheme.typography.bodySmall)
        if(mode=="Controller") {
            Text("Creates a nearby local Wi-Fi Direct group for up to 3 Agents. No mobile data hotspot or Bluetooth connection is needed.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=!busy&&p2p.available&&session.isNotBlank(),onClick={requestStart("controller")}){
                    Text(if(p2p.controllerMode)"Restart Group" else "Start Controller")
                }
                OutlinedButton(enabled=p2p.running,onClick={p2p.stop();tick++}){Text("Stop")}
            }
            Text("Registered Agents (${p2p.agents().size})",fontWeight=FontWeight.Medium)
            p2p.agents().forEach{agent->
                HorizontalDivider()
                Text("${agent.role} · ${agent.name.ifBlank{"Unknown model"}}",fontWeight=FontWeight.Medium)
                Text("Device ${agent.deviceId.take(12)} · ${agent.ip}",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled=!busy&&agent.role!=role&&agent.session==session,onClick={
                    onJob{ExperienceLanV130.fetch(context,repository,agent.ip,agent.key,session,agent.role)}
                }){Text("Collect ${agent.role}")}
            }
            val agents=p2p.agents().filter{it.role!=role && it.session==session}
            Button(enabled=!busy&&agents.isNotEmpty()&&agents.map{it.role}.distinct().size==agents.size,onClick={
                onJob {
                    val local=runCatching{repository.scanLocal(session,date,scenario,operator,rat,role)}
                        .fold(onSuccess={"Controller local: $it new readings"},onFailure={"Controller local scan: ${it.message}"})
                    local+"\n"+agents.joinToString("\n"){a->"${a.role}: "+runCatching{
                        ExperienceLanV130.fetch(context,repository,a.ip,a.key,session,a.role)
                    }.getOrElse{"Failed: ${it.message}"}}
                }
            }){Text("Collect All Connected Agents")}
            Text("After collection: Data → choose dates and test items → Apply Selected Reports → Compare / Reports. Missing Agents can reconnect and retry.",style=MaterialTheme.typography.bodySmall)
        }else {
            Text("Scan lists only verified CellTracker Wi-Fi Direct services, not neighboring hotspots. Android may show a system confirmation.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=!busy&&p2p.available,onClick={requestStart("scan")}){Text("Find Controllers")}
                OutlinedButton(enabled=p2p.running,onClick={p2p.stop();tick++}){Text("Disconnect")}
            }
            Text("Found Controllers (${p2p.hosts().size})",fontWeight=FontWeight.Medium)
            p2p.hosts().forEach{host->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text(host.name.ifBlank{"CellTracker Controller"},fontWeight=FontWeight.Medium)
                        Text(host.address.take(18),style=MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled=!busy&&!p2p.connected,onClick={runCatching{p2p.connect(host)}.onFailure{onMessage(it.message.orEmpty())};tick++}){Text("Connect")}
                }
            }
            if(p2p.connected&&!p2p.isOwner) {
                Text("Connected: preparing and registering the report automatically.",style=MaterialTheme.typography.bodySmall)
                Button(enabled=!busy&&session.isNotBlank(),onClick={
                    onJob{
                        val b=server.start(session,date,scenario,operator,rat,role)
                        val r=p2p.announce(role,session,server.accessCode)
                        "${b.rowCount} measurements / ${b.files} raw reports ready. $r"
                    }
                }){Text(if(server.isSharing)"Refresh Shared Reports" else "Retry Report Sharing")}
                if(server.isSharing){
                    Text("Local archive ready · ${server.report?.rowCount} readings · ${if(server.isSynced)"Synced" else "Awaiting collection"}",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={server.stop();tick++}){Text("Stop Sharing")}
                }
            }
        }
        TextButton(onClick={tick++}){Text("Refresh Connection Status")}
    } }
}
