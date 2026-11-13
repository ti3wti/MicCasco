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
import android.widget.Switch
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
    private lateinit var smartCheck: Switch
    private lateinit var modeText: TextView
    private lateinit var batteryBtn: Button
    private val radioToAddr = HashMap<Int, Pair<String, String>>()
    private lateinit var updateText: TextView
    private lateinit var updateBtn: Button
    private var latest: Updater.Release? = null
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Lang.load(this)
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

    private fun t(es: String, en: String) = Lang.t(es, en)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        val langGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; gravity = Gravity.END }
        val rbEs = RadioButton(this).apply { id = View.generateViewId(); text = "Español" }
        val rbEn = RadioButton(this).apply { id = View.generateViewId(); text = "English" }
        langGroup.addView(rbEs)
        langGroup.addView(rbEn)
        langGroup.check(if (Lang.english) rbEn.id else rbEs.id)
        langGroup.setOnCheckedChangeListener { _, id ->
            val code = if (id == rbEn.id) "en" else "es"
            if (code == prefs.lang) return@setOnCheckedChangeListener
            prefs.lang = code
            Lang.load(this)
            if (MicRouteService.running) {
                val app = applicationContext
                MicRouteService.stop(app)
                android.os.Handler(mainLooper).postDelayed({
                    try { MicRouteService.start(app) } catch (_: Exception) {}
                }, 600)
            }
            recreate()
        }
        root.addView(langGroup, LinearLayout.LayoutParams(-1, -2))

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

        root.addView(section(t("Prueba del micrófono", "Microphone test")))
        testBtn = Button(this).apply {
            text = t("Grabar 5 s y escuchar", "Record 5 s and listen")
            setOnClickListener { runTest() }
        }
        root.addView(testBtn)
        testResult = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, 0) }
        root.addView(testResult)

        root.addView(section(t("Headset preferido", "Preferred headset")))
        deviceGroup = RadioGroup(this)
        deviceGroup.setOnCheckedChangeListener { _, id ->
            val p = radioToAddr[id] ?: return@setOnCheckedChangeListener
            prefs.deviceAddress = p.first
            prefs.deviceName = p.second
            autoCheck.isEnabled = p.first.isNotEmpty()
        }
        root.addView(deviceGroup)

        autoCheck = CheckBox(this).apply {
            text = t("Activar solo al conectar este headset y desactivar al desconectarlo", "Turn on only when this headset connects and off when it disconnects")
            isChecked = prefs.auto
            setOnCheckedChangeListener { _, c -> prefs.auto = c }
        }
        root.addView(autoCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(section(t("Modo de funcionamiento", "Operating mode")))
        smartCheck = Switch(this).apply {
            text = t("Cambio automático", "Automatic switching")
            textSize = 16f
            isChecked = prefs.smart
            setOnCheckedChangeListener { _, c ->
                prefs.smart = c
                keepCheck.isEnabled = !c
                updateModeText()
                if (MicRouteService.running) {
                    MicRouteService.stop(this@MainActivity)
                    toggleBtn.postDelayed({ startRoute() }, 600)
                }
            }
        }
        root.addView(smartCheck, LinearLayout.LayoutParams(-1, -2))
        modeText = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(modeText)
        updateModeText()

        keepCheck = CheckBox(this).apply {
            text = t("Mantener el canal del casco abierto (recomendado)", "Keep the helmet channel open (recommended)")
            isChecked = prefs.keepAlive
            isEnabled = !prefs.smart
            setOnCheckedChangeListener { _, c -> prefs.keepAlive = c }
        }
        root.addView(keepCheck)

        batteryBtn = Button(this).apply {
            text = t("Quitar optimización de batería", "Remove battery optimization")
            setOnClickListener { requestBatteryExemption() }
        }
        root.addView(batteryBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        root.addView(section(t("Actualizaciones", "Updates")))
        updateText = TextView(this).apply { textSize = 14f }
        root.addView(updateText)
        updateBtn = Button(this).apply {
            text = t("Buscar actualización", "Check for updates")
            setOnClickListener { onUpdateClick() }
        }
        root.addView(updateBtn)

        root.addView(section(t("Notas", "Notes")))
        root.addView(TextView(this).apply {
            textSize = 13f
            text = t(
                """
                • Cambio automático: la música va en estéreo (A2DP) y solo cuando una app empieza a grabar (WhatsApp, Asistente…) se cambia al canal de voz (HFP). El cambio tarda ≈1 s: espere un instante antes de hablar.
                • Siempre activo: todo el audio del casco pasa por el canal de voz; la música se escucha en mono y con menor calidad.
                • Agregue el botón "Mic Casco" a los Ajustes rápidos (deslice la barra hacia abajo › lápiz/editar) para activarlo sin abrir la app.
                • Para el Asistente: actívelo con el botón del headset o el de la pantalla. La detección de "Ok Google" la hace un chip del teléfono, pero una vez abierto el Asistente, la orden se escucha por el casco.
                • Si el headset está emparejado con la pantalla AOOCCI y no con el teléfono, elija la pantalla como headset preferido.
                • En las llamadas telefónicas normales la app no interviene.
                """.trimIndent(),
                """
                • Automatic switching: music plays in stereo (A2DP) and only when an app starts recording (WhatsApp, Assistant…) does it switch to the voice channel (HFP). The switch takes ≈1 s: wait a moment before speaking.
                • Always on: all helmet audio uses the voice channel; music sounds mono and at lower quality.
                • Add the "Mic Casco" tile to Quick Settings (swipe down › pencil/edit) to turn it on without opening the app.
                • For the Assistant: start it with the headset or screen button. "Hey Google" is detected by a chip in the phone, but once the Assistant is open, your command is heard through the helmet.
                • If the headset is paired with the AOOCCI screen instead of the phone, select the screen as preferred headset.
                • The app does not interfere with regular phone calls.
                """.trimIndent()
            )
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun updateModeText() {
        modeText.text = if (smartCheck.isChecked)
            t("ENCENDIDO: música en estéreo; el micrófono del casco se activa solo mientras una app graba (WhatsApp, Asistente…). Espere ≈1 s antes de hablar.", "ON: music in stereo; the helmet microphone turns on only while an app records (WhatsApp, Assistant…). Wait ≈1 s before speaking.")
        else
            t("APAGADO (siempre activo): el micrófono del casco queda activo todo el tiempo; la música se escucha en mono. Es el modo más confiable.", "OFF (always on): the helmet microphone stays active all the time; music sounds mono. This is the most reliable mode.")
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun refresh() {
        val on = MicRouteService.running
        statusText.text = if (on) MicRouteService.status else t("Inactivo: se usa el micrófono del teléfono", "Inactive: using the phone microphone")
        statusText.setBackgroundColor(if (on) Color.rgb(200, 240, 200) else Color.rgb(235, 235, 235))
        toggleBtn.text = if (on) t("DESACTIVAR", "TURN OFF") else t("ACTIVAR MICRÓFONO DEL CASCO", "ACTIVATE HELMET MICROPHONE")
        val pm = getSystemService(PowerManager::class.java)
        batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        deviceGroup.removeAllViews()
        radioToAddr.clear()
        val list = ArrayList<Pair<String, String>>()
        list.add("" to t("Cualquier headset Bluetooth", "Any Bluetooth headset"))
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
            statusText.text = t("No se pudo iniciar: ${e.message}", "Could not start: ${e.message}")
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
        updateText.text = t("Versión instalada: $installed. Buscando…", "Installed version: $installed. Checking…")
        Thread {
            val r = try { Updater.fetchLatest() } catch (_: Exception) { null }
            runOnUiThread {
                checking = false
                latest = r
                when {
                    r == null -> {
                        updateText.text = t("Versión instalada: $installed. No se pudo consultar GitHub.", "Installed version: $installed. Could not reach GitHub.")
                        updateBtn.text = t("Buscar actualización", "Check for updates")
                    }
                    r.version > installed -> {
                        updateText.text = t("¡Hay una versión nueva (${r.version})! Instalada: $installed.", "New version available (${r.version})! Installed: $installed.")
                        updateBtn.text = t("INSTALAR VERSIÓN ${r.version}", "INSTALL VERSION ${r.version}")
                    }
                    else -> {
                        updateText.text = t("Versión $installed: está al día.", "Version $installed: up to date.")
                        updateBtn.text = t("Buscar actualización", "Check for updates")
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
            updateText.text = t("Permita a Mic Casco instalar apps y vuelva a tocar el botón.", "Allow Mic Casco to install apps and tap the button again.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updateBtn.isEnabled = false
        Thread {
            try {
                Updater.downloadAndInstall(this, r.apkUrl) { p ->
                    runOnUiThread { updateBtn.text = t("Descargando… $p %", "Downloading… $p %") }
                }
                runOnUiThread { updateBtn.text = t("Instalando…", "Installing…") }
            } catch (e: Exception) {
                runOnUiThread {
                    updateText.text = t("Error al descargar: ${e.message}", "Download error: ${e.message}")
                    updateBtn.text = t("INSTALAR VERSIÓN ${r.version}", "INSTALL VERSION ${r.version}")
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
        testBtn.text = t("Grabando 5 s… hable ahora", "Recording 5 s… speak now")
        testResult.text = ""
        Thread {
            val rate = 16000
            val total = rate * 5
            val data = ShortArray(total)
            var source = t("desconocido", "unknown")
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
                if (source == t("desconocido", "unknown")) source = describe(rec.routedDevice)
                rec.stop()
                rec.release()

                runOnUiThread { testBtn.text = t("Reproduciendo…", "Playing back…") }
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
                testBtn.text = t("Grabar 5 s y escuchar", "Record 5 s and listen")
                testResult.text = t("Se grabó desde: $result", "Recorded from: $result")
            }
        }.start()
    }

    private fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return t("desconocido", "unknown")
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> t("CASCO / Bluetooth ✔", "HELMET / Bluetooth ✔")
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> t("micrófono del TELÉFONO ✘", "PHONE microphone ✘")
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> t("audífono con cable", "wired headset")
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            else -> if (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                t("CASCO / Bluetooth LE ✔", "HELMET / Bluetooth LE ✔") else t("tipo ${d.type}", "type ${d.type}")
        }
        return "$kind (${d.productName})"
    }
}
