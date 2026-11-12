package cr.wmarin.miccasco

import android.content.Context

/** Configuración persistente de la app. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    /** Activar automáticamente al conectar el headset elegido. */
    var auto: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** Dirección MAC del headset preferido ("" = cualquiera). */
    var deviceAddress: String
        get() = sp.getString("addr", "") ?: ""
        set(v) = sp.edit().putString("addr", v).apply()

    var deviceName: String
        get() = sp.getString("name", "") ?: ""
        set(v) = sp.edit().putString("name", v).apply()

    /** Reproduce silencio en canal de voz para que Android no cierre el enlace SCO. */
    var keepAlive: Boolean
        get() = sp.getBoolean("keepAlive", true)
        set(v) = sp.edit().putBoolean("keepAlive", v).apply()
}
