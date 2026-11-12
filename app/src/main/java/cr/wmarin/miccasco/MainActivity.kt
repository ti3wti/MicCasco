package cr.wmarin.miccasco

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    companion object {
        const val EXTRA_START = "start"
        private const val REQ_BASE = 10
        private const val REQ_MIC = 11
    }

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var testBtn: Button
    private lateinit var testResult: TextView
    private lateinit var deviceGroup: RadioGroup
    private lateinit var autoCheck: CheckBox
    private lateinit var keepCheck: CheckBox
    private lateinit var batteryBtn: Button
    private val radioToAddr = HashMap<Int, Pair<String, String>>()
    private lateinit var updateText: TextView
    private lateinit var updateBtn: Button
    private var latest: Updater.Release? = null
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        askBasePermissions()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MicRouteService.listener = { runOnUiThread { refresh() } }
        loadDevices()
        refresh()
        checkUpdates()
    }

    override fun onPause() {
        MicRouteService.listener = null
        super.onPause()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_START, false) == true) {
            i.removeExtra(EXTRA_START)
            startRoute()
        }
    }

    // ---------------- Interfaz ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        statusText = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        toggleBtn = Button(this).apply {
            textSize = 18f
            minHeight = dp(72)
            setOnClickListener { if (MicRouteService.running) MicRouteService.stop(this@MainActivity) else startRoute() }
        }
        root.addView(toggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(section("Prueba del micrófono"))
        testBtn = Button(this).apply {
            text = "Grabar 5 s y escuchar"
            setOnClickListener { runTest() }
        }
        root.addView(testBtn)
        testResult = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, 0) }
        root.addView(testResult)

        root.addView(section("Headset preferido"))
        deviceGroup = RadioGroup(this)
        deviceGroup.setOnCheckedChangeListener { _, id ->
            val p = radioToAddr[id] ?: return@setOnCheckedChangeListener
            prefs.deviceAddress = p.first
            prefs.deviceName = p.second
            autoCheck.isEnabled = p.first.isNotEmpty()
        }
        root.addView(deviceGroup)

        autoCheck = CheckBox(this).apply {
            text = "Activar solo al conectar este headset y desactivar al desconectarlo"
            isChecked = prefs.auto
            setOnCheckedChangeListener { _, c -> prefs.auto = c }
        }
        root.addView(autoCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        keepCheck = CheckBox(this).apply {
            text = "Mantener el canal del casco abierto (recomendado)"
            isChecked = prefs.keepAlive
            setOnCheckedChangeListener { _, c -> prefs.keepAlive = c }
        }
        root.addView(keepCheck)

        batteryBtn = Button(this).apply {
            text = "Quitar optimización de batería"
            setOnClickListener { requestBatteryExemption() }
        }
        root.addView(batteryBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        root.addView(section("Actualizaciones"))
        updateText = TextView(this).apply { textSize = 14f }
        root.addView(updateText)
        updateBtn = Button(this).apply {
            text = "Buscar actualización"
            setOnClickListener { onUpdateClick() }
        }
        root.addView(updateBtn)

        root.addView(section("Notas"))
        root.addView(TextView(this).apply {
            textSize = 13f
            text = """
                • Mientras esté activo, todo el audio del casco pasa por el canal de voz (HFP): la música se escucha en mono y con menor calidad. Desactívelo cuando solo quiera escuchar música.
                • Agregue el botón "Mic Casco" a los Ajustes rápidos (desliza la barra hacia abajo › lápiz/editar) para activarlo sin abrir la app.
                • Para el Asistente: actívelo con el botón del headset o el de la pantalla. La detección de "Ok Google" la hace un chip del teléfono, pero una vez abierto el Asistente, la orden se escucha por el casco.
                • Si el headset está emparejado con la pantalla AOOCCI y no con el teléfono, elija la pantalla como headset preferido.
                • En las llamadas telefónicas normales la app no interviene.
            """.trimIndent()
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun refresh() {
        val on = MicRouteService.running
        statusText.text = if (on) MicRouteService.status else "Inactivo: se usa el micrófono del teléfono"
        statusText.setBackgroundColor(if (on) Color.rgb(200, 240, 200) else Color.rgb(235, 235, 235))
        toggleBtn.text = if (on) "DESACTIVAR" else "ACTIVAR MICRÓFONO DEL CASCO"
        val pm = getSystemService(PowerManager::class.java)
        batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        deviceGroup.removeAllViews()
        radioToAddr.clear()
        val list = ArrayList<Pair<String, String>>()
        list.add("" to "Cualquier headset Bluetooth")
        if (hasBtPermission()) {
            try {
                val adapter = getSystemService(BluetoothManager::class.java).adapter
                adapter?.bondedDevices?.sortedBy { it.name ?: "" }?.forEach {
                    list.add(it.address to (it.name ?: it.address))
                }
            } catch (_: SecurityException) {}
        }
        var checkId = -1
        list.forEach { (addr, name) ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = if (addr.isEmpty()) name else "$name  ($addr)"
            }
            radioToAddr[rb.id] = addr to name
            deviceGroup.addView(rb)
            if (addr.equals(prefs.deviceAddress, ignoreCase = true)) checkId = rb.id
        }
        if (checkId != -1) deviceGroup.check(checkId)
        autoCheck.isEnabled = prefs.deviceAddress.isNotEmpty()
    }

    // ---------------- Acciones ----------------

    private fun startRoute() {
        if (!hasBtPermission()) {
            askBasePermissions()
            return
        }
        try {
            MicRouteService.start(this)
        } catch (e: Exception) {
            statusText.text = "No se pudo iniciar: ${e.message}"
        }
    }

    private fun hasBtPermission() =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun askBasePermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_BASE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BASE) loadDevices()
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) runTest()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------- Actualizaciones ----------------

    private fun checkUpdates() {
        if (checking) return
        checking = true
        val installed = Updater.installedVersion(this)
        updateText.text = "Versión instalada: $installed. Buscando…"
        Thread {
            val r = try { Updater.fetchLatest() } catch (_: Exception) { null }
            runOnUiThread {
                checking = false
                latest = r
                when {
                    r == null -> {
                        updateText.text = "Versión instalada: $installed. No se pudo consultar GitHub."
                        updateBtn.text = "Buscar actualización"
                    }
                    r.version > installed -> {
                        updateText.text = "¡Hay una versión nueva (${r.version})! Instalada: $installed."
                        updateBtn.text = "INSTALAR VERSIÓN ${r.version}"
                    }
                    else -> {
                        updateText.text = "Versión $installed: está al día."
                        updateBtn.text = "Buscar actualización"
                    }
                }
            }
        }.start()
    }

    private fun onUpdateClick() {
        val r = latest
        if (r == null || r.version <= Updater.installedVersion(this)) {
            checkUpdates()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            updateText.text = "Permita a Mic Casco instalar apps y vuelva a tocar el botón."
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updateBtn.isEnabled = false
        Thread {
            try {
                Updater.downloadAndInstall(this, r.apkUrl) { p ->
                    runOnUiThread { updateBtn.text = "Descargando… $p %" }
                }
                runOnUiThread { updateBtn.text = "Instalando…" }
            } catch (e: Exception) {
                runOnUiThread {
                    updateText.text = "Error al descargar: ${e.message}"
                    updateBtn.text = "INSTALAR VERSIÓN ${r.version}"
                }
            }
            runOnUiThread { updateBtn.isEnabled = true }
        }.start()
    }

    /** Graba 5 s con la fuente MIC (la misma que usan las notas de voz) y lo reproduce. */
    @SuppressLint("MissingPermission")
    private fun runTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        testBtn.isEnabled = false
        testBtn.text = "Grabando 5 s… hable ahora"
        testResult.text = ""
        Thread {
            val rate = 16000
            val total = rate * 5
            val data = ShortArray(total)
            var source = "desconocido"
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192)
                )
                rec.startRecording()
                var off = 0
                while (off < total) {
                    val n = rec.read(data, off, min(2048, total - off))
                    if (n <= 0) break
                    if (off == 0) source = describe(rec.routedDevice)
                    off += n
                }
                if (source == "desconocido") source = describe(rec.routedDevice)
                rec.stop()
                rec.release()

                runOnUiThread { testBtn.text = "Reproduciendo…" }
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
                    .setBufferSizeInBytes(total * 2)
                    .build()
                track.write(data, 0, total)
                track.play()
                Thread.sleep(5300)
                track.stop()
                track.release()
            } catch (e: Exception) {
                source = "error: ${e.message}"
            }
            val result = source
            runOnUiThread {
                testBtn.isEnabled = true
                testBtn.text = "Grabar 5 s y escuchar"
                testResult.text = "Se grabó desde: $result"
            }
        }.start()
    }

    private fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "desconocido"
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "CASCO / Bluetooth ✔"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micrófono del TELÉFONO ✘"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "audífono con cable"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                "CASCO / Bluetooth LE ✔" else "tipo ${d.type}"
        }
        return "$kind (${d.productName})"
    }
}
