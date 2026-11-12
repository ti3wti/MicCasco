package cr.wmarin.miccasco

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Botón en los Ajustes rápidos (barra de notificaciones) para activar/desactivar. */
class MicTileService : TileService() {

    companion object {
        fun refresh(ctx: Context) {
            try {
                requestListeningState(ctx, ComponentName(ctx, MicTileService::class.java))
            } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        if (MicRouteService.running) {
            MicRouteService.stop(this)
        } else {
            try {
                MicRouteService.start(this)
            } catch (_: Exception) {
                openApp()
            }
        }
        handler.postDelayed({ update() }, 400)
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (MicRouteService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (MicRouteService.running) "Casco" else "Teléfono"
        }
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_START, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
