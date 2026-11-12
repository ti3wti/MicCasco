package cr.wmarin.miccasco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Servicio en primer plano que obliga a Android a usar el micrófono
 * Bluetooth (perfil HFP/SCO) del casco para TODA la captura de audio:
 * notas de voz de WhatsApp, Asistente de Google, grabadoras, etc.
 *
 * Cómo lo logra:
 *  1. NO cambia el modo de audio (si se pone MODE_IN_COMMUNICATION,
 *     WhatsApp cree que hay una llamada y bloquea las notas de voz).
 *  2. Android 12+: setCommunicationDevice(<headset SCO>).
 *     Android 8–11: startBluetoothSco().
 *     En ambos casos el sistema fuerza FOR_RECORD = BT_SCO, así que
 *     cualquier app que grabe con MIC / VOICE_RECOGNITION usa el casco.
 *  3. (Opcional) reproduce silencio por el canal de voz para que Android
 *     no reinicie el modo por inactividad (lo hace tras ~6 s sin audio).
 *  4. Vigila cada 5 s y reaplica si otra app cambió la ruta. Durante una
 *     llamada telefónica normal no interviene.
 */
class MicRouteService : Service() {

    companion object {
        private const val TAG = "MicCasco"
        const val ACTION_STOP = "cr.wmarin.miccasco.STOP"
        private const val CHANNEL_ID = "mic_casco"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set
        @Volatile var status = "Inactivo"
            private set

        /** La interfaz se suscribe aquí para refrescarse. */
        @Volatile var listener: (() -> Unit)? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MicRouteService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MicRouteService::class.java))
        }
    }

    private lateinit var am: AudioManager
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var silentTrack: AudioTrack? = null
    private var scoReceiverRegistered = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            handler.postDelayed({ applyRoute() }, 800)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            handler.postDelayed({ applyRoute() }, 300)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            applyRoute()
            handler.postDelayed(this, 5000)
        }
    }

    /** Solo se usa en Android 8–11. */
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    setStatus("Micrófono del casco ACTIVO")
                    startKeepAlive()
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    setStatus("Esperando headset Bluetooth…")
                    handler.postDelayed({ applyRoute() }, 2000)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground("Activando…")) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            am.registerAudioDeviceCallback(deviceCallback, handler)
            if (Build.VERSION.SDK_INT < 31 && !scoReceiverRegistered) {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                scoReceiverRegistered = true
            }
        }
        applyRoute()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 5000)
        MicTileService.refresh(this)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { am.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        if (scoReceiverRegistered) {
            try { unregisterReceiver(scoReceiver) } catch (_: Exception) {}
            scoReceiverRegistered = false
        }
        stopKeepAlive()
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar ruta", e)
        }
        status = "Inactivo"
        listener?.invoke()
        MicTileService.refresh(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun applyRoute() {
        if (!running) return
        try {
            val mode = am.mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE ||
                (Build.VERSION.SDK_INT >= 30 && mode == AudioManager.MODE_CALL_SCREENING)
            ) {
                setStatus("Llamada telefónica en curso (en pausa)")
                return
            }

            if (Build.VERSION.SDK_INT >= 31) {
                val target = pickDevice(am.availableCommunicationDevices)
                if (target == null) {
                    stopKeepAlive()
                    setStatus("Esperando headset Bluetooth…")
                    return
                }
                val current = am.communicationDevice
                if (current?.id != target.id) {
                    if (!am.setCommunicationDevice(target)) {
                        setStatus("No se pudo seleccionar ${target.productName}")
                        return
                    }
                }
                startKeepAlive()
                setStatus("Micrófono del casco ACTIVO: ${target.productName}")
            } else {
                @Suppress("DEPRECATION")
                if (!am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    setStatus("Conectando con el casco…")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyRoute", e)
            setStatus("Error: ${e.message}")
        }
    }

    private fun pickDevice(list: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val bt = list.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (bt.isEmpty()) return null
        val wanted = prefs.deviceAddress
        if (wanted.isNotEmpty()) {
            bt.firstOrNull { it.address.equals(wanted, ignoreCase = true) }?.let { return it }
        }
        return bt.first()
    }

    // ---- canal silencioso para mantener vivo el enlace SCO -------------

    private fun startKeepAlive() {
        if (!prefs.keepAlive || silentTrack != null) return
        try {
            val rate = 16000
            val frames = rate / 2 // 0,5 s en bucle
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            track.write(ShortArray(frames), 0, frames)
            track.setLoopPoints(0, frames, -1)
            track.play()
            silentTrack = track
        } catch (e: Exception) {
            Log.w(TAG, "keepAlive", e)
        }
    }

    private fun stopKeepAlive() {
        silentTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        silentTrack = null
    }

    // ---- notificación ---------------------------------------------------

    private fun setStatus(s: String) {
        if (s == status) return
        status = s
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
        listener?.invoke()
        MicTileService.refresh(this)
    }

    private fun goForeground(text: String): Boolean {
        return try {
            val n = buildNotification(text)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar en primer plano", e)
            status = "Falta permiso de Bluetooth o notificaciones"
            listener?.invoke()
            false
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Micrófono del casco", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicRouteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Mic Casco")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Desactivar", stop).build())
            .build()
    }
}
