@file:Suppress("DEPRECATION")

package com.example.celltracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.net.wifi.WpsInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * After-test discovery ONLY. Wi-Fi Direct DNS-SD advertises our app-specific service;
 * unrelated Wi-Fi access points and P2P peers are deliberately never displayed.
 * Existing Bluetooth Call Setup and existing verified LAN archive protocol stay separate.
 */
internal class ExperienceP2pV130(private val context: Context) {
    data class Host(val name:String,val address:String)
    data class Agent(val deviceId:String,val name:String,val role:String,val ip:String,val key:String,val session:String)
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)
    private val io = Executors.newSingleThreadExecutor()
    private var advertised:WifiP2pDnsSdServiceInfo? = null
    private var request:WifiP2pDnsSdServiceRequest? = null
    private var receiverRegistered=false
    private var ownerCreated=false
    @Volatile private var coordinator:ServerSocket?=null
    @Volatile private var coordinatorStarting=false
    private val agents=linkedMapOf<String,Agent>()
    private val hosts=linkedMapOf<String,Host>()
    @Volatile var status="Not connected";private set
    @Volatile var groupOwnerIp="";private set
    @Volatile var isOwner=false;private set
    @Volatile var connected=false;private set
    @Volatile var controllerMode=false;private set
    @Volatile var running=false;private set
    var expectedSession=""
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(c:Context,i:Intent){
            if(i.action==WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION){
                updateConnection()
            } else if(i.action==WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION){
                val enabled=i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,-1)==WifiP2pManager.WIFI_P2P_STATE_ENABLED
                if(!enabled){connected=false;groupOwnerIp="";status="Wi-Fi Direct is off"}
            }
        }
    }
    val available get()=manager!=null && channel!=null
    fun hosts():List<Host> = synchronized(hosts){hosts.values.toList()}
    fun agents():List<Agent> = synchronized(agents){agents.values.toList()}
    @SuppressLint("MissingPermission")
    private fun startReceiver(){
        if(receiverRegistered)return
        val filter=IntentFilter().apply{
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver,filter)
        receiverRegistered=true
    }
    @SuppressLint("MissingPermission")
    private fun updateConnection(){
        val m=manager?:return;val ch=channel?:return
        runCatching{m.requestConnectionInfo(ch){info->
            if(info.groupFormed){
                connected=true;isOwner=info.isGroupOwner
                groupOwnerIp=info.groupOwnerAddress?.hostAddress.orEmpty()
                status=if(isOwner)"Controller group ready; awaiting Agents" else "Connected to CellTracker Controller"
                if(isOwner && controllerMode && groupOwnerIp.isNotBlank()) startCoordinator()
            }else{
                connected=false;groupOwnerIp=""
                if(running)status="Awaiting Wi-Fi Direct connection"
            }
        }}
    }
    @SuppressLint("MissingPermission")
    fun startController(session:String){
        require(available){"Wi-Fi Direct is unsupported by this phone"}
        require(session.isNotBlank()){"Select an analysis Session first"}
        stop()
        controllerMode=true;expectedSession=session;running=true;status="Creating CellTracker Wi-Fi Direct group…"
        startReceiver()
        val m=manager!!;val ch=channel!!
        val service=WifiP2pDnsSdServiceInfo.newInstance("CellTrackerController","_celltracker._tcp",
            mapOf("app" to "CellTracker","purpose" to "posttest-report-sync","version" to "1"))
        advertised=service
        m.addLocalService(ch,service,object:WifiP2pManager.ActionListener{
            override fun onSuccess() {
                m.createGroup(ch,object:WifiP2pManager.ActionListener{
                    override fun onSuccess(){ownerCreated=true;status="Creating group; waiting for Agents…";updateConnection()}
                    override fun onFailure(reason:Int){status="Could not start group (reason $reason); try shared LAN fallback"}
                })
            }
            override fun onFailure(reason:Int){status="Could not publish Controller service ($reason)"}
        })
    }
    @SuppressLint("MissingPermission")
    fun findControllers(){
        require(available){"Wi-Fi Direct is unsupported by this phone"}
        if(controllerMode)stop()
        controllerMode=false;running=true
        startReceiver()
        synchronized(hosts){hosts.clear()}
        val m=manager!!;val ch=channel!!
        m.setDnsSdResponseListeners(ch,
            WifiP2pManager.DnsSdServiceResponseListener { name, type, device ->
                // Service name filtering alone isn't enough: insist on our exact service type.
                if(ExperiencePeerFilterV130.matchesService(name,type)){
                    synchronized(hosts){hosts[device.deviceAddress]=Host(device.deviceName.ifBlank{"CellTracker Controller"},device.deviceAddress)}
                    status="CellTracker Controller discovered"
                }
            },
            WifiP2pManager.DnsSdTxtRecordListener { domain,record,device ->
                if(ExperiencePeerFilterV130.matchesTxt(domain,record)){
                    synchronized(hosts){hosts[device.deviceAddress]=Host(device.deviceName.ifBlank{"CellTracker Controller"},device.deviceAddress)}
                    status="CellTracker Controller discovered"
                }
            })
        request?.let{m.removeServiceRequest(ch,it,null)}
        val req=WifiP2pDnsSdServiceRequest.newInstance("_celltracker._tcp");request=req
        m.addServiceRequest(ch,req,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){
                m.discoverServices(ch,object:WifiP2pManager.ActionListener{
                    override fun onSuccess(){status="Searching for CellTracker Controllers…"}
                    override fun onFailure(reason:Int){status="Discovery failed ($reason). Enable location and Wi-Fi Direct."}
                })
            }
            override fun onFailure(reason:Int){status="Service discovery unavailable ($reason)"}
        })
    }
    @SuppressLint("MissingPermission")
    fun connect(host:Host){
        require(available){"Wi-Fi Direct unavailable"}
        require(host.address in hosts().map{it.address}){"Select a verified CellTracker Controller"}
        val config=WifiP2pConfig().apply{deviceAddress=host.address;wps.setup=WpsInfo.PBC;groupOwnerIntent=0}
        status="Connecting to ${host.name}; accept Android's Wi-Fi Direct prompt if shown…"
        manager!!.connect(channel!!,config,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){status="Connection requested; waiting for Controller…"}
            override fun onFailure(reason:Int){status="Wi-Fi Direct connection failed ($reason)"}
        })
    }
    /** Agent announces its archive endpoint *after* its local report snapshot is ready. */
    fun announce(agentRole:String,session:String,accessCode:String):String{
        require(!controllerMode && connected && !isOwner && groupOwnerIp.isNotBlank()) {"Connect to Controller first"}
        require(agentRole in ExperienceAnalyzer.roles && session.isNotBlank() && accessCode.length==12)
        val owner=groupOwnerIp
        Socket().use{s->
            s.connect(java.net.InetSocketAddress(owner,48732),6000)
            s.soTimeout=8000
            val payload=JSONObject().apply{
                put("protocol",1);put("app","CellTracker");put("session",session)
                put("deviceId",ExperienceLanV130.deviceId(context));put("model",Build.MODEL.orEmpty())
                put("role",agentRole);put("key",accessCode)
            }
            s.getOutputStream().write((payload.toString()+"\n").toByteArray(Charsets.UTF_8));s.getOutputStream().flush()
            val response=BufferedReader(InputStreamReader(s.getInputStream(),Charsets.UTF_8)).readLine().orEmpty()
            require(response=="OK"){"Controller registration rejected: $response"}
            return "Registered with Controller. Keep sharing active until the Controller collects your report."
        }
    }
    /** Each registration is restricted to group members, limited in size, and tied to a Session. */
    private fun startCoordinator(){
        if(coordinatorStarting || coordinator!=null || !controllerMode || !isOwner || groupOwnerIp.isBlank())return
        coordinatorStarting=true
        io.execute {
            try {
                if(!controllerMode)return@execute
                // Bind only to the Wi-Fi Direct group owner interface, never 0.0.0.0.
                val server=ServerSocket(48732,5,InetAddress.getByName(groupOwnerIp))
                coordinator=server
                while(!server.isClosed && controllerMode){
                    val client=try{server.accept()}catch(_:Exception){break}
                    client.use{s->try{
                        s.soTimeout=6500
                        val line=BufferedReader(InputStreamReader(s.getInputStream(),Charsets.UTF_8)).readLine()?.take(2049).orEmpty()
                        require(line.length in 2..2048){"Registration too large"}
                        val o=JSONObject(line)
                        val ip=s.inetAddress.hostAddress.orEmpty()
                        require(ExperienceLanV130.safeAddress(ip)==ip)
                        require(o.optInt("protocol")==1 && o.optString("app")=="CellTracker")
                        require(o.optString("session")==expectedSession)
                        val role=o.optString("role");require(role in ExperienceAnalyzer.roles)
                        val key=o.optString("key");require(key.matches(Regex("[A-Z2-9]{12}")))
                        val id=o.optString("deviceId");require(id.matches(Regex("[A-Za-z0-9-]{8,64}")))
                        val model=o.optString("model").take(100)
                        synchronized(agents){agents[id]=Agent(id,model,role,ip,key,expectedSession)}
                        s.getOutputStream().write("OK\n".toByteArray())
                        status="${agents().size} Agent(s) registered; ready to collect"
                    }catch(_:Exception){runCatching{s.getOutputStream().write("REJECTED\n".toByteArray())}}}
                }
            }catch(e:Exception){if(controllerMode)status="Controller registration service failed: ${e.message}"}
            finally { coordinatorStarting=false;coordinator=null }
        }
    }
    @SuppressLint("MissingPermission")
    fun stop(){
        controllerMode=false;running=false
        runCatching{coordinator?.close()};coordinator=null
        val m=manager;val ch=channel
        if(m!=null && ch!=null){
            request?.let{runCatching{m.removeServiceRequest(ch,it,null)}};request=null
            advertised?.let{runCatching{m.removeLocalService(ch,it,null)}};advertised=null
            if(ownerCreated)runCatching{m.removeGroup(ch,null)}
        }
        ownerCreated=false
        coordinatorStarting=false
        if(receiverRegistered){runCatching{context.unregisterReceiver(receiver)};receiverRegistered=false}
        synchronized(agents){agents.clear()};synchronized(hosts){hosts.clear()}
        connected=false;isOwner=false;groupOwnerIp="";status="Not connected"
    }
    fun dispose(){stop();io.shutdownNow()}
}
