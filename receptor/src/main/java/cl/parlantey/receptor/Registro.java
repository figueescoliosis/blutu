package cl.parlantey.receptor;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Diagnóstico para NOTAS.md: etiqueta de logcat ({@code adb logcat -s ParlanteY}), último error
 * guardado en {@code ultimo_error.txt} y contador de reinicios del servicio.
 */
final class Registro {

    static final String TAG = "ParlanteY";

    private static final String ARCHIVO_ERROR = "ultimo_error.txt";
    private static final String PREFERENCIAS = "diagnostico";
    private static final String REINICIOS = "reinicios";

    private Registro() {
    }

    /** Guarda la traza en ultimo_error.txt (reemplaza la anterior) y la manda a logcat. */
    static synchronized void guardarError(Context contexto, String donde, Throwable t) {
        Log.e(TAG, "error en " + donde, t);
        FileOutputStream salida = null;
        try {
            salida = contexto.openFileOutput(ARCHIVO_ERROR, Context.MODE_PRIVATE);
            PrintStream p = new PrintStream(salida, false, "UTF-8");
            p.println(new java.util.Date() + " · " + donde);
            t.printStackTrace(p);
            p.flush();
        } catch (IOException e) {
            Log.w(TAG, "no se pudo guardar el error", e);
        } finally {
            cerrar(salida);
        }
    }

    /** Primera línea útil del último error guardado ("" si no hay). */
    static String ultimoError(Context contexto) {
        File archivo = new File(contexto.getFilesDir(), ARCHIVO_ERROR);
        if (!archivo.exists()) {
            return "";
        }
        FileInputStream entrada = null;
        try {
            entrada = new FileInputStream(archivo);
            byte[] b = new byte[(int) Math.min(archivo.length(), 600)];
            int n = entrada.read(b);
            if (n <= 0) {
                return "";
            }
            String[] lineas = new String(b, 0, n, "UTF-8").split("\n");
            return lineas.length >= 2 ? lineas[0] + "\n" + lineas[1] : lineas[0];
        } catch (IOException e) {
            return "";
        } finally {
            cerrar(entrada);
        }
    }

    /** Instala un manejador que guarda las excepciones no capturadas antes de dejar caer la app. */
    static void instalarManejador(final Context contexto) {
        final Thread.UncaughtExceptionHandler anterior = Thread.getDefaultUncaughtExceptionHandler();
        if (anterior instanceof Manejador) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new Manejador(contexto.getApplicationContext(), anterior));
    }

    static int reinicios(Context contexto) {
        return preferencias(contexto).getInt(REINICIOS, 0);
    }

    /** El sistema recreó el servicio (START_STICKY) tras matar el proceso. */
    static void contarReinicio(Context contexto) {
        SharedPreferences p = preferencias(contexto);
        p.edit().putInt(REINICIOS, p.getInt(REINICIOS, 0) + 1).apply();
    }

    private static SharedPreferences preferencias(Context contexto) {
        return contexto.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE);
    }

    private static void cerrar(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignorada) {
                // Nada que hacer.
            }
        }
    }

    private static final class Manejador implements Thread.UncaughtExceptionHandler {
        private final Context contexto;
        private final Thread.UncaughtExceptionHandler anterior;

        Manejador(Context contexto, Thread.UncaughtExceptionHandler anterior) {
            this.contexto = contexto;
            this.anterior = anterior;
        }

        @Override
        public void uncaughtException(Thread hilo, Throwable t) {
            guardarError(contexto, "hilo " + hilo.getName() + " (no capturada)", t);
            if (anterior != null) {
                anterior.uncaughtException(hilo, t);
            }
        }
    }
}
