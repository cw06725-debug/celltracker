package com.example.celltracker

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSetupScreen(
    link: DeviceLinkState,
    test: CallSetupTestState,
    history: List<CallSetupHistoryItem>,
    detail: CallSetupDetail?,
    onBack:()->Unit,
    onLinkAction:(String,String)->Unit,
    onSelectLocalSim:(Int)->Unit,
    onSaveIdentity:(String,Int)->Unit,
    onStartTest:(CallSetupConfig)->Unit,
    onStopTest:()->Unit,
    onOpenDetail:(String)->Unit,
    onCloseDetail:()->Unit,
    onExport:(String)->Unit
) {
    // Keep the history scroll position while a detail page temporarily replaces this content.
    // Returning from View Details now lands exactly where the user entered it.
    val historyListState = rememberSaveable(saver=androidx.compose.foundation.lazy.LazyListState.Saver) { androidx.compose.foundation.lazy.LazyListState() }
    if(detail!=null){BackHandler{onCloseDetail()};CallSetupDetailView(detail,onCloseDetail){onExport(detail.item.path)};return}
    val context=LocalContext.current
    val focusManager=LocalFocusManager.current
    val keyboardController=LocalSoftwareKeyboardController.current
    val snackbar=remember{SnackbarHostState()}
    val uiScope=rememberCoroutineScope()
    var permissionEpoch by remember{mutableIntStateOf(0)}
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ permissionEpoch++ ; onLinkAction(DeviceLinkService.ACTION_REFRESH,"") }
    val enableLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){ onLinkAction(DeviceLinkService.ACTION_REFRESH,"") }
    val discoverableLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){ permissionEpoch++; onLinkAction(DeviceLinkService.ACTION_REFRESH,"") }
    LaunchedEffect(Unit){
        val p=mutableListOf(Manifest.permission.CALL_PHONE,Manifest.permission.ANSWER_PHONE_CALLS,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_PHONE_NUMBERS)
        if(Build.VERSION.SDK_INT>=31){p+=Manifest.permission.BLUETOOTH_SCAN;p+=Manifest.permission.BLUETOOTH_CONNECT;p+=Manifest.permission.BLUETOOTH_ADVERTISE}
        permissionLauncher.launch(p.toTypedArray())
        onLinkAction(DeviceLinkService.ACTION_REFRESH,"")
    }
    val identityRepo=remember{CallSetupRepository(context)}
    val activeSlots=remember(permissionEpoch,link.localProfile.subscriptionId){activeSimSlots(context)}
    var localSim by remember(link.localProfile.simSlot){mutableIntStateOf(link.localProfile.simSlot)}
    val phoneBySlot=remember{mutableStateMapOf(0 to identityRepo.loadLocalNumber(0),1 to identityRepo.loadLocalNumber(1))}
    LaunchedEffect(activeSlots){
        if(activeSlots.isNotEmpty() && localSim !in activeSlots){ localSim=activeSlots.first(); onSelectLocalSim(localSim) }
        activeSlots.forEach{slot-> if(phoneBySlot[slot].isNullOrBlank()) detectPhoneNumber(context,slot)?.let{phoneBySlot[slot]=it} }
    }
    var task by remember(test.config.taskName){mutableStateOf(test.config.taskName)}
    var count by remember(test.config.callCount){mutableStateOf(test.config.callCount.toString())}
    var timeout by remember(test.config.setupTimeoutMs){mutableStateOf((test.config.setupTimeoutMs/1000).toString())}
    var hold by remember(test.config.holdTimeMs){mutableStateOf((test.config.holdTimeMs/1000).toString())}
    var interval by remember(test.config.interCallIntervalMs){mutableStateOf((test.config.interCallIntervalMs/1000).toString())}
    var threshold by remember(test.config.highLatencyThresholdMs){mutableStateOf((test.config.highLatencyThresholdMs/1000).toString())}
    var direction by remember(test.config.direction){mutableStateOf(test.config.direction)}
    var autoRecord by remember(test.config.autoRecord){mutableStateOf(test.config.autoRecord)}
    var mode by remember(test.config.automationMode){mutableStateOf(test.config.automationMode)}
    var aSim by remember(test.config.aCallSimSlot){mutableIntStateOf(test.config.aCallSimSlot)}
    var bSim by remember(test.config.bCallSimSlot){mutableIntStateOf(test.config.bCallSimSlot)}
    LaunchedEffect(activeSlots){if(activeSlots.isNotEmpty()&&aSim !in activeSlots)aSim=activeSlots.first()}
    LaunchedEffect(link.peerProfile){val slots=listOf(0,1).filter{!link.peerProfile?.phoneForSlot(it).isNullOrBlank()};if(slots.isNotEmpty()&&bSim !in slots)bSim=slots.first()}
    Scaffold(
        topBar={TopAppBar(title={Text("Device Link + Call Setup")},navigationIcon={TextButton(onClick=onBack){Text("Back")}})},
        snackbarHost={SnackbarHost(snackbar)}
    ){pad->
        LazyColumn(Modifier.padding(pad).fillMaxSize(),state=historyListState,contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{CCard("Local DUT"){
                CField("Device",link.localProfile.deviceName);CField("Device ID",link.localProfile.deviceId.take(12));CField("CellTracker",link.localProfile.appVersion)
                if(activeSlots.isEmpty()) Text("No active SIM detected. Check SIM/phone permissions.",style=MaterialTheme.typography.bodySmall)
                else activeSlots.forEach{slot->
                    OutlinedTextField(phoneBySlot[slot].orEmpty(),{v->phoneBySlot[slot]=v.filter{ch->ch.isDigit()||ch=='+'||ch=='#'||ch=='*'}.take(24)},label={Text("SIM ${slot+1} phone number")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!test.isRunning)
                }
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Local SIM")
                    activeSlots.forEach{slot->
                        if(localSim==slot) Button(onClick={localSim=slot;onSelectLocalSim(slot)},enabled=!test.isRunning){Text("SIM ${slot+1}")}
                        else OutlinedButton(onClick={localSim=slot;onSelectLocalSim(slot)},enabled=!test.isRunning){Text("SIM ${slot+1}")}
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={
                        val detected=detectPhoneNumber(context,localSim)
                        if(detected!=null){phoneBySlot[localSim]=detected;uiScope.launch{snackbar.showSnackbar("Phone number detected for SIM ${localSim+1}")}}
                        else uiScope.launch{snackbar.showSnackbar("Phone number unavailable; please enter it manually")}
                    },enabled=!test.isRunning&&activeSlots.isNotEmpty()){Text("Auto Detect")}
                    Button(onClick={
                        activeSlots.forEach{slot->identityRepo.saveLocalIdentity(phoneBySlot[slot].orEmpty(),slot)}
                        onSaveIdentity(phoneBySlot[localSim].orEmpty(),localSim)
                        focusManager.clearFocus(force=true);keyboardController?.hide();uiScope.launch{snackbar.showSnackbar("Saved")}
                    },enabled=!test.isRunning&&activeSlots.isNotEmpty()){Text("Save")}
                }
            }}
            item{CCard("Bluetooth Device Link"){
                val bluetoothPermissionsGranted=bluetoothPermissionsGranted(context)
                CField("Bluetooth",if(link.bluetoothEnabled)"On" else "Off")
                CField("Permissions",if(bluetoothPermissionsGranted)"Granted" else "Required")
                CField("Mode",link.role.name)
                CField("State",link.status.name)
                CField("Status",link.statusMessage)
                CField("Discovery",if(link.discoveryActive)"Scanning" else "Idle")
                if(link.role==DeviceLinkRole.AGENT)CField("Discoverable",if(link.discoverable)"Yes" else "No")
                CField("Peer",link.peerProfile?.let{"${it.deviceName} · ${it.deviceId.take(8)}"}?:link.peer?.name?:"--")
                CField("Link RTT",link.latencyMs?.let{String.format(Locale.US,"%.1f ms",it)}?:"--");CField("Clock offset estimate",link.clockOffsetMs?.let{String.format(Locale.US,"%.1f ms",it)}?:"--")
                if(!bluetoothPermissionsGranted)OutlinedButton(onClick={
                    val p=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    permissionLauncher.launch(p)
                }){Text("Grant Bluetooth permissions")}
                if(!link.bluetoothEnabled)Button(onClick={enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))}){Text("Turn on Bluetooth")}
                if(link.role==DeviceLinkRole.CONTROLLER){
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={
                            onLinkAction(DeviceLinkService.ACTION_AGENT,"")
                        },enabled=!test.isRunning){Text("Switch to Agent")}
                        Button(onClick={onLinkAction(DeviceLinkService.ACTION_DISCOVER,"")},enabled=!test.isRunning&&link.status!=DeviceLinkStatus.CONNECTED){Text(if(link.discoveryActive)"Scan again" else "Scan")}
                    }
                }else{
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(
                            onClick={onLinkAction(DeviceLinkService.ACTION_CONTROLLER,"")},
                            enabled=!test.isRunning,
                            modifier=Modifier.fillMaxWidth()
                        ){Text("Switch to Controller")}
                        OutlinedButton(
                            onClick={discoverableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,300))},
                            enabled=!test.isRunning,
                            modifier=Modifier.fillMaxWidth()
                        ){Text("Make Discoverable")}
                    }
                    Text(
                        if(link.discoverable) "Agent is discoverable and waiting for Controller."
                        else "Agent RFCOMM server is waiting. Tap Make Discoverable; Android will show a system confirmation dialog for discoverability.",
                        style=MaterialTheme.typography.bodySmall
                    )
                }
                if(link.status==DeviceLinkStatus.CONNECTED)Button(onClick={onLinkAction(DeviceLinkService.ACTION_DISCONNECT,"")},enabled=!test.isRunning){Text("Disconnect")}
            }}
            if(link.role==DeviceLinkRole.CONTROLLER && link.status!=DeviceLinkStatus.CONNECTED){
                item{Text("Paired devices",style=MaterialTheme.typography.titleMedium)}
                if(link.pairedDevices.isEmpty())item{Text("No paired devices",style=MaterialTheme.typography.bodySmall)}else items(link.pairedDevices,key={"paired-${it.address}"}){d->DeviceRow(d,onConnect={onLinkAction(DeviceLinkService.ACTION_CONNECT,d.address)},onPair=null,enabled=!test.isRunning)}
                item{Text("Nearby devices",style=MaterialTheme.typography.titleMedium)}
                if(link.discoveredDevices.isEmpty())item{Text(if(link.discoveryActive)"Scanning…" else "No nearby devices found. Tap Scan above.",style=MaterialTheme.typography.bodySmall)}else items(link.discoveredDevices,key={"nearby-${it.address}"}){d->DeviceRow(d,onConnect={onLinkAction(DeviceLinkService.ACTION_CONNECT,d.address)},onPair=if(d.bonded)null else{{onLinkAction(DeviceLinkService.ACTION_PAIR,d.address)}},enabled=!test.isRunning)}
            }
            if(link.role==DeviceLinkRole.AGENT){item{AgentPanel(link,test,onStopTest)}}
            if(link.role==DeviceLinkRole.CONTROLLER){
                item{CCard("Call Setup configuration"){
                    OutlinedTextField(task,{task=it.take(64)},label={Text("Task Name")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!test.isRunning)
                    Text("Direction",style=MaterialTheme.typography.labelLarge);CallDirection.entries.forEach{d->Row(Modifier.fillMaxWidth().clickable(enabled=!test.isRunning){direction=d},verticalAlignment=Alignment.CenterVertically){RadioButton(direction==d,{direction=d},enabled=!test.isRunning);Text(directionLabel(d))}}
                    NumberPair("Call Count",count,{count=digits(it,3)},"Setup Timeout (s)",timeout,{timeout=digits(it,3)},!test.isRunning)
                    NumberPair("Hold Time (s)",hold,{hold=digits(it,4)},"Inter-call (s)",interval,{interval=digits(it,4)},!test.isRunning)
                    OutlinedTextField(threshold,{threshold=digits(it,3)},label={Text("High Setup Latency (s)")},singleLine=true,enabled=!test.isRunning,modifier=Modifier.fillMaxWidth())
                    val aSlots=activeSlots.ifEmpty{listOf(localSim)}
                    val bSlots=listOf(0,1).filter{!link.peerProfile?.phoneForSlot(it).isNullOrBlank()}.ifEmpty{listOf(link.peerProfile?.simSlot?:0)}
                    Text("A Call SIM", style=MaterialTheme.typography.labelLarge)
                    SimSelector(aSim,{aSim=it},!test.isRunning,aSlots)
                    Text("B Call SIM", style=MaterialTheme.typography.labelLarge)
                    SimSelector(bSim,{bSim=it},!test.isRunning,bSlots)
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(true,{},enabled=false);Text("Network recording on both DUTs (automatic)")}
                    Text("Call Setup automatically records the continuous radio/GPS trace for analysis and report export.",style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically){Checkbox(mode==AutomationMode.SEMI_AUTO,{mode=if(it)AutomationMode.SEMI_AUTO else AutomationMode.AUTO_WHEN_AVAILABLE},enabled=!test.isRunning);Text("Force Semi-Auto mode")}
                    Text("Public Android call APIs are used. If auto answer/hang-up is unavailable, manually operate the Phone app; state detection and results continue automatically.",style=MaterialTheme.typography.bodySmall)
                    val selectedANumber=phoneBySlot[aSim].orEmpty()
                    val selectedBNumber=link.peerProfile?.phoneForSlot(bSim).orEmpty()
                    val canStart=link.status==DeviceLinkStatus.CONNECTED&&selectedANumber.isNotBlank()&&selectedBNumber.isNotBlank()
                    if(test.isRunning)Button(onClick=onStopTest){Text("STOP TEST")}else Button(onClick={onStartTest(CallSetupConfig(task.ifBlank{"CallSetup"},direction,count.toIntOrNull()?:10,(timeout.toLongOrNull()?:30)*1000,(hold.toLongOrNull()?:10)*1000,(interval.toLongOrNull()?:10)*1000,(threshold.toLongOrNull()?:8)*1000,autoRecord,aSim,bSim,mode))},enabled=canStart){Text("Start Call Setup Test")}
                    if(!test.isRunning&&!canStart) Text(when{link.status!=DeviceLinkStatus.CONNECTED->"Connect DUT B first.";selectedANumber.isBlank()->"Save DUT A SIM ${aSim+1} phone number first.";else->"Save DUT B SIM ${bSim+1} phone number on the Agent first."},style=MaterialTheme.typography.bodySmall)
                }}
                item{LiveTestPanel(link,test)}
            }
            item{Text("Call Setup History",style=MaterialTheme.typography.titleMedium)}
            if(history.isEmpty())item{Text("No saved Call Setup sessions",style=MaterialTheme.typography.bodySmall)}else items(history,key={it.path}){h->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(h.taskName,style=MaterialTheme.typography.titleSmall)
                        Text("${h.deviceA} ↔ ${h.deviceB} · ${h.direction}",style=MaterialTheme.typography.bodySmall)
                        Text("${h.attempts} attempts · Success ${String.format(Locale.US,"%.1f%%",h.successRate)} · Avg ${ms(h.averageMs)}",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Button(onClick={onOpenDetail(h.path)},modifier=Modifier.weight(1f)){Text("View Details")}
                            OutlinedButton(onClick={onExport(h.path)},modifier=Modifier.weight(1f)){Text("Export Report")}
                        }
                    }
                }
            }
        }
    }
}

private fun bluetoothPermissionsGranted(context:Context):Boolean = if(Build.VERSION.SDK_INT>=31){
    ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
}else ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
private fun activeSimSlots(context:Context):List<Int> = runCatching {
    if(ContextCompat.checkSelfPermission(context,Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED) return@runCatching emptyList()
    val sm=context.getSystemService(SubscriptionManager::class.java)
    @Suppress("MissingPermission") sm.activeSubscriptionInfoList.orEmpty().map{it.simSlotIndex}.filter{it>=0}.distinct().sorted()
}.getOrDefault(emptyList())

private fun detectPhoneNumber(context:Context,simSlot:Int):String?{
    val readGranted=ContextCompat.checkSelfPermission(context,Manifest.permission.READ_PHONE_NUMBERS)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED
    if(!readGranted)return null
    return runCatching{
        val sm=context.getSystemService(SubscriptionManager::class.java)
        @Suppress("MissingPermission")
        val info=sm.activeSubscriptionInfoList?.firstOrNull{it.simSlotIndex==simSlot}?:return@runCatching null
        val tm=context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(info.subscriptionId)
        val candidates=buildList<String?>{
            if(Build.VERSION.SDK_INT>=33) add(runCatching{sm.getPhoneNumber(info.subscriptionId)}.getOrNull())
            @Suppress("DEPRECATION") add(runCatching{info.number}.getOrNull())
            @Suppress("DEPRECATION") add(runCatching{tm.line1Number}.getOrNull())
        }
        candidates.asSequence().mapNotNull{it?.trim()}.firstOrNull{it.isNotBlank()}
    }.getOrNull()
}


@Composable private fun DeviceRow(d:BluetoothPeer,onConnect:()->Unit,onPair:(()->Unit)?,enabled:Boolean){Card(Modifier.fillMaxWidth()){Row(Modifier.padding(12.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(d.name);Text(d.address,style=MaterialTheme.typography.bodySmall)};if(onPair!=null)OutlinedButton(onClick=onPair,enabled=enabled){Text("Pair")};Spacer(Modifier.width(6.dp));Button(onClick=onConnect,enabled=enabled&&d.bonded){Text("Connect")}}}}
@Composable private fun AgentPanel(link:DeviceLinkState,test:CallSetupTestState,onStop:()->Unit){CCard("Agent status"){CField("Controller",link.peerProfile?.deviceName?:"--");CField("Session",test.sessionId.ifBlank{"--"});CField("Direction",test.currentDirection);CField("Role",test.localRole);CField("Attempt",test.currentAttempt.toString());CField("Call State",test.localCallState);CField("RAT / Voice RAT",test.localSnapshot?.let{"${it.displayRat} / ${it.voiceRat}"}?:"--");CField("Signal",test.localSnapshot?.rsrp?:"--");if(test.isRunning)Button(onClick=onStop,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("EMERGENCY STOP TEST")}}}
@Composable private fun LiveTestPanel(link:DeviceLinkState,s:CallSetupTestState){CCard("Live Controller result"){CField("Status",s.statusMessage);CField("Attempt","${s.currentAttempt} / ${expectedAttempts(s.config)}");CField("Direction",s.currentDirection);CField("DUT A / ${s.localRole}","${s.localSnapshot?.operator?:link.localProfile.operator} · ${s.localSnapshot?.displayRat?:link.localProfile.rat} · ${s.localSnapshot?.voiceRat?:link.localProfile.voiceRat} · ${s.localCallState}");CField("DUT B / ${s.peerRole}","${s.peerSnapshot?.operator?:link.peerProfile?.operator?:"--"} · ${s.peerSnapshot?.displayRat?:link.peerProfile?.rat?:"--"} · ${s.peerSnapshot?.voiceRat?:link.peerProfile?.voiceRat?:"--"} · ${s.peerCallState}");CField("Success / Failure","${s.success} / ${s.failure}");CField("Success Rate",String.format(Locale.US,"%.1f%%",s.successRate));CField("Current / Avg", "${s.currentSetupLatencyMs?.let{"$it ms"}?:"--"} / ${ms(s.averageMs)}");CField("Min / Max","${ms(s.minMs)} / ${ms(s.maxMs)}");CField("P50 / P90 / P95","${ms(s.p50Ms)} / ${ms(s.p90Ms)} / ${ms(s.p95Ms)}");CField("Consecutive failures",s.consecutiveFailures.toString());CField("Bluetooth",link.status.name);CField("Automation",s.automationCapability)}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CallSetupDetailView(d:CallSetupDetail,onBack:()->Unit,onExport:()->Unit){
    var tab by remember{mutableIntStateOf(0)}
    val tabs=listOf("Summary","Attempts","Events","Map","Trend")
    Scaffold(topBar={TopAppBar(title={Text(d.item.taskName)},navigationIcon={TextButton(onClick=onBack){Text("Back")}},actions={TextButton(onClick=onExport){Text("Export")}})}){p->
        Column(Modifier.padding(p).fillMaxSize()){
            Surface(modifier=Modifier.fillMaxWidth().zIndex(20f),color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp){
                ScrollableTabRow(selectedTabIndex=tab,containerColor=MaterialTheme.colorScheme.surface){tabs.forEachIndexed{i,t->Tab(tab==i,{tab=i},text={Text(t)})}}
            }
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()){
            when(tab){
                0->LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{CCard("Summary"){
                        CField("DUT A / B","${d.item.deviceA} / ${d.item.deviceB}")
                        CField("Operator A / B","${d.item.operatorA} / ${d.item.operatorB}")
                        CField("Direction",d.item.direction)
                        CField("Attempts","${d.item.attempts} · Success ${d.item.success} · Failure ${d.item.failure}")
                        CField("Success Rate",String.format(Locale.US,"%.1f%%",d.item.successRate))
                        CField("Avg / P90 / P95","${ms(d.item.averageMs)} / ${ms(d.item.p90Ms)} / ${ms(d.item.p95Ms)}")
                        CField("Start / End","${date(d.item.startedAt)} / ${date(d.item.endedAt)}")
                        CField("Status",d.item.status)
                        Button(onClick=onExport,modifier=Modifier.fillMaxWidth()){Text("Export Call Setup Report")}
                    }}
                }
                1->AttemptList(d.attempts)
                2->EventList(d.events)
                3->CallMap(d)
                else->CallTrend(d.attempts,d.item.highLatencyThresholdMs)
            }
            }
        }
    }
}
@Composable private fun AttemptList(a:List<CallAttemptResult>){LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(a,key={it.attemptId}){x->var open by remember(x.attemptId){mutableStateOf(false)};Card(Modifier.fillMaxWidth().clickable{open=!open}){Column(Modifier.padding(10.dp)){Text("#${x.attemptNumber} ${x.direction} · ${x.result}",style=MaterialTheme.typography.titleSmall);Text("Setup ${x.setupLatencyMs?.let{"$it ms"}?:"--"} · ${x.confidence}",style=MaterialTheme.typography.bodySmall);Text("Dial ${x.dialAt?.let(::date)?:"--"} · MT Ring ${x.mtRingingAt?.let(::date)?:"--"}",style=MaterialTheme.typography.bodySmall);Text("MO Connected ${x.moConnectedAt?.let(::date)?:"--"} · MT Connected ${x.mtConnectedAt?.let(::date)?:"--"}",style=MaterialTheme.typography.bodySmall);Text("End ${x.callEndedAt?.let(::date)?:"--"}",style=MaterialTheme.typography.bodySmall);if(x.failureDetail.isNotBlank())Text(x.failureDetail,style=MaterialTheme.typography.bodySmall);if(open)x.snapshots.sortedBy{it.timestampMs}.forEach{s->Text("${s.endpoint} ${s.moment}: ${s.displayRat}/${s.voiceRat}, RSRP ${s.rsrp}, PCI ${s.pci}, ${date(s.timestampMs)}",style=MaterialTheme.typography.bodySmall)}}}}}}
@Composable private fun EventList(events:List<CallSetupEvent>){LazyColumn(contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(events){e->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(e.type,style=MaterialTheme.typography.titleSmall);Text("${e.source} · ${e.direction} · ${e.attemptId}",style=MaterialTheme.typography.bodySmall);Text(date(e.timestampMs),style=MaterialTheme.typography.bodySmall);if(e.detail.isNotBlank())Text(e.detail,style=MaterialTheme.typography.bodySmall)}}}}}
@Composable private fun CallMap(detail:CallSetupDetail){
    // Prefer the continuous Recording trace that automation starts on this DUT.
    // Older sessions fall back to the sparse call snapshots so existing history stays viewable.
    val route=remember(detail){
        detail.networkSamples.filter{it.locationValid&&it.latitude!=null&&it.longitude!=null}.sortedBy{it.timestampMs}
    }
    val fallback=remember(detail.attempts){
        detail.attempts.flatMap{a->a.snapshots.map{s->a to s}}
            .filter{it.second.latitude!=null&&it.second.longitude!=null}
            .sortedBy{it.second.timestampMs}
    }
    val routePoints=remember(route,fallback){
        if(route.isNotEmpty()) route.map{GeoPoint(it.latitude!!,it.longitude!!)}
        else fallback.map{GeoPoint(it.second.latitude!!,it.second.longitude!!)}
    }
    if(routePoints.isEmpty()){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            Text("No valid GPS track. Automated sessions will include the continuous Recording route when GPS is available.")
        }
        return
    }
    // A failed attempt is painted at the nearest continuous route sample. No large default
    // osmdroid pin icons are used, so map controls remain unobstructed.
    val failurePoints=remember(route,detail.attempts,fallback){
        detail.attempts.filter{it.result!="SUCCESS"}.mapNotNull{a->
            val t=(a.endedAt.takeIf{it>0}?:a.startedAt)
            if(route.isNotEmpty()) route.minByOrNull{kotlin.math.abs(it.timestampMs-t)}?.let{GeoPoint(it.latitude!!,it.longitude!!)}
            else a.snapshots.filter{it.latitude!=null&&it.longitude!=null}.minByOrNull{kotlin.math.abs(it.timestampMs-t)}?.let{GeoPoint(it.latitude!!,it.longitude!!)}
        }
    }
    val context=LocalContext.current
    val osmTileSource=remember{
        XYTileSource("OpenStreetMap",0,19,256,".png",arrayOf("https://tile.openstreetmap.org/"),"© OpenStreetMap contributors")
    }
    val map=remember(context){MapView(context).apply{
        setTileSource(osmTileSource);setUseDataConnection(true);setMultiTouchControls(true);minZoomLevel=3.0;maxZoomLevel=19.0
    }}
    DisposableEffect(map){map.onResume();onDispose{map.onPause()}}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Box(Modifier.size(10.dp).background(Color(0xFF1976D2),androidx.compose.foundation.shape.CircleShape));Text("Route (${routePoints.size})",style=MaterialTheme.typography.bodySmall)}
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){Box(Modifier.size(10.dp).background(Color(0xFFD32F2F),androidx.compose.foundation.shape.CircleShape));Text("Call fail (${failurePoints.size})",style=MaterialTheme.typography.bodySmall)}
        }
        AndroidView(
            factory={map.apply{
                overlays.clear()
                overlays.add(CallTrackDotsOverlay(routePoints,failurePoints))
                post{
                    val bounds=BoundingBox.fromGeoPoints(routePoints)
                    if(routePoints.size==1){controller.setCenter(routePoints.first());controller.setZoom(17.0)}
                    else zoomToBoundingBox(bounds,false,80)
                    invalidate()
                }
            }},
            modifier=Modifier.fillMaxWidth().weight(1f).clipToBounds(),
            update={}
        )
    }
}

private class CallTrackDotsOverlay(
    private val route:List<GeoPoint>,
    private val failures:List<GeoPoint>
):Overlay(){
    private val normalPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(25,118,210);style=android.graphics.Paint.Style.FILL}
    private val failPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=android.graphics.Color.rgb(211,47,47);style=android.graphics.Paint.Style.FILL}
    private val p=android.graphics.Point()
    override fun draw(canvas:android.graphics.Canvas,mapView:MapView,shadow:Boolean){
        if(shadow)return
        val projection=mapView.projection
        route.forEach{g->projection.toPixels(g,p);canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),4f,normalPaint)}
        failures.forEach{g->projection.toPixels(g,p);canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),9f,failPaint)}
    }
}

@Composable private fun CallTrend(a:List<CallAttemptResult>,threshold:Long){val v=a.mapNotNull{it.setupLatencyMs?.toDouble()};if(a.isEmpty()){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("No attempts")};return};Column(Modifier.padding(16.dp)){Text("Setup latency by attempt",style=MaterialTheme.typography.titleMedium);Text("Blue: success · Red: failure · Orange: high latency threshold",style=MaterialTheme.typography.bodySmall);Canvas(Modifier.fillMaxWidth().height(280.dp).background(MaterialTheme.colorScheme.surfaceVariant)){val top=maxOf(v.maxOrNull()?:1.0,threshold.toDouble(),1.0)*1.15;val thresholdY=size.height-(threshold/top*size.height).toFloat();drawLine(Color(0xFFFF9800),androidx.compose.ui.geometry.Offset(0f,thresholdY),androidx.compose.ui.geometry.Offset(size.width,thresholdY),3f);val n=a.size.coerceAtLeast(2);a.forEachIndexed{i,x->val xx=if(a.size==1)size.width/2 else i.toFloat()/(n-1)*size.width;val y=x.setupLatencyMs?.let{size.height-(it/top*size.height).toFloat()};if(y==null)drawCircle(Color.Red,7f,androidx.compose.ui.geometry.Offset(xx,size.height-8))else drawCircle(if(x.result=="SUCCESS")Color.Blue else Color.Red,7f,androidx.compose.ui.geometry.Offset(xx,y))}}}}

@Composable private fun CCard(title:String,content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text(title,style=MaterialTheme.typography.titleMedium);content()}}}
@Composable private fun CField(k:String,v:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(k,style=MaterialTheme.typography.bodySmall);Text(v,style=MaterialTheme.typography.bodySmall)}}
@Composable private fun NumberPair(a:String,av:String,ac:(String)->Unit,b:String,bv:String,bc:(String)->Unit,e:Boolean){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(av,ac,label={Text(a)},singleLine=true,enabled=e,modifier=Modifier.weight(1f));OutlinedTextField(bv,bc,label={Text(b)},singleLine=true,enabled=e,modifier=Modifier.weight(1f))}}
@Composable private fun SimSelector(v:Int,set:(Int)->Unit,e:Boolean,slots:List<Int> = listOf(0,1)){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        slots.forEach{s->
            if(v==s) Button(onClick={set(s)},enabled=e,modifier=Modifier.weight(1f)){Text("SIM ${s+1}")}
            else OutlinedButton(onClick={set(s)},enabled=e,modifier=Modifier.weight(1f)){Text("SIM ${s+1}")}
        }
    }
}
private fun digits(s:String,n:Int)=s.filter{it.isDigit()}.take(n)
private fun ms(v:Double?)=v?.let{String.format(Locale.US,"%.1f ms",it)}?:"--"
private fun date(v:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.getDefault()).format(Date(v))
private fun expectedAttempts(c:CallSetupConfig)=if(c.direction==CallDirection.BIDIRECTIONAL_BLOCK)c.callCount*2 else c.callCount
private fun directionLabel(d:CallDirection)=when(d){CallDirection.A_TO_B->"A → B";CallDirection.B_TO_A->"B → A";CallDirection.BIDIRECTIONAL_BLOCK->"Bidirectional blocks (A→B × N, then B→A × N)";CallDirection.BIDIRECTIONAL_ALTERNATE->"Bidirectional alternating"}
