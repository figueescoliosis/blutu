package cl.parlantey.receptor;

import cl.parlantey.core.Track;

/** Textos para la UI sin String.format (que en Dalvik asigna bastante en cada llamada). */
final class Formato {

    private Formato() {
    }

    /** {@code decimas} / 10 con una cifra decimal y coma: 123 → "12,3". */
    static String decimal(long decimas) {
        long abs = Math.abs(decimas);
        return (decimas < 0 ? "-" : "") + (abs / 10) + "," + (abs % 10);
    }

    /** "Título — Artista (3:25)": sin artista o sin duración (en vivo), sin esa parte. */
    static String track(Track t) {
        if (t == null) {
            return "";
        }
        String texto = t.artista.length() == 0 ? t.titulo : t.titulo + " — " + t.artista;
        return t.duracionMs > 0 ? texto + " (" + duracion(t.duracionMs) + ")" : texto;
    }

    /** 205000 → "3:25"; 3725000 → "1:02:05". */
    static String duracion(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = s / 60 % 60;
        long seg = s % 60;
        String resto = (seg < 10 ? ":0" : ":") + seg;
        if (h > 0) {
            return h + (m < 10 ? ":0" : ":") + m + resto;
        }
        return m + resto;
    }
}
