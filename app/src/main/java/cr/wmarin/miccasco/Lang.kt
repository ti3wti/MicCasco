package cr.wmarin.miccasco

import android.content.Context

/** Idioma de la interfaz: español (predeterminado) o inglés. */
object Lang {
    @Volatile var english = false
        private set

    fun load(ctx: Context) {
        english = Prefs(ctx).lang == "en"
    }

    fun t(es: String, en: String) = if (english) en else es
}
