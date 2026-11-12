package cr.wmarin.miccasco

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Activa/desactiva el servicio automáticamente cuando el headset
 * elegido se conecta o desconecta por Bluetooth.
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.auto || prefs.deviceAddress.isEmpty()) return

        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val addr = try { dev?.address } catch (_: SecurityException) { null } ?: return
        if (!addr.equals(prefs.deviceAddress, ignoreCase = true)) return

        try {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> MicRouteService.start(context)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> MicRouteService.stop(context)
            }
        } catch (e: Exception) {
            Log.w("MicCasco", "Inicio automático bloqueado por el sistema", e)
        }
    }
}
