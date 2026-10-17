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

    /** "Título — Artista", o solo el título si no hay artista. */
    static String track(Track t) {
        if (t == null) {
            return "";
        }
        return t.artista.length() == 0 ? t.titulo : t.titulo + " — " + t.artista;
    }
}
