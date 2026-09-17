package com.example.celltracker
import android.content.*
import android.hardware.usb.UsbManager
class UsbAdbPermissionReceiver:BroadcastReceiver(){
 override fun onReceive(context:Context,intent:Intent){
  if(intent.action=="com.example.celltracker.USB_ADB_PERMISSION"){
   val ok=intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)
   CellTrackerAdbEngine.refreshUsbRef(context)
   AdbToolStore.state.value=AdbToolStore.state.value.copy(message=if(ok)"USB permission granted · press CONNECT USB ADB" else "USB permission denied")
  }
 }
}
