package cl.parlantey.emisor

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.core.content.edit

/**
 * Silencia la música de este teléfono mientras envía todo el audio, como si tuviera audífonos.
 * En el Samsung A35 (Android 16) la captura no depende del volumen (NOTAS.md, Fase 4 ②): el Galaxy Y
 * sigue sonando y se regula con sus teclas. El volumen anterior se guarda en SharedPreferences, así
 * se restaura aunque Android haya cerrado la app en medio. Si el usuario lo cambió mientras tanto,
 * se respeta.
 */
class Silenciador(contexto: Context) {
    private val am = contexto.getSystemService(AudioManager::class.java)
    private val prefs = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun silenciar() {
        if (prefs.contains(CLAVE)) return // ya está silenciado por nosotros
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (v == 0) return
        prefs.edit(commit = true) { putInt(CLAVE, v) }
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: SecurityException) {
            Log.w(Supervisor.TAG, "no se pudo silenciar", e)
            prefs.edit(commit = true) { remove(CLAVE) }
        }
    }

    fun restaurar() {
        val v = prefs.getInt(CLAVE, -1)
        if (v < 0) return
        prefs.edit(commit = true) { remove(CLAVE) }
        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        } catch (e: SecurityException) {
            Log.w(Supervisor.TAG, "no se pudo restaurar el volumen", e)
        }
    }

    companion object {
        private const val PREFS = "silencio"
        private const val CLAVE = "volumenAntes"
    }
}
