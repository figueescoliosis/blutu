package cl.parlantey.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Payload de TRACK (E→R): {@code título UTF, artista UTF, duraciónMs u32} (0 = en vivo). */
public final class Track {

    public final String titulo;
    public final String artista;
    public final long duracionMs;

    public Track(String titulo, String artista, long duracionMs) {
        if (titulo == null || artista == null) {
            throw new IllegalArgumentException("título y artista no pueden ser nulos");
        }
        Rangos.u32(duracionMs, "duracionMs");
        this.titulo = titulo;
        this.artista = artista;
        this.duracionMs = duracionMs;
    }

    void escribir(DataOutputStream d) throws IOException {
        d.writeUTF(titulo);
        d.writeUTF(artista);
        d.writeInt((int) duracionMs);
    }

    public static Track leer(DataInputStream d) throws IOException {
        String titulo = d.readUTF();
        String artista = d.readUTF();
        long duracionMs = d.readInt() & 0xFFFFFFFFL;
        return new Track(titulo, artista, duracionMs);
    }

    @Override
    public String toString() {
        return "Track{\"" + titulo + "\" de \"" + artista + "\", " + duracionMs + " ms}";
    }
}
