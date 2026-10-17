package cl.parlantey.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Payload de CONFIG (E→R): {@code códec u8, sampleRate u32, canales u8, muestrasPorBloque u16,
 * bufferObjetivoMs u32}. Define el formato de los AUDIO que siguen.
 */
public final class Config {

    public final int codec;
    public final int sampleRate;
    public final int canales;
    public final int muestrasPorBloque;
    public final long bufferObjetivoMs;

    /** @throws IllegalArgumentException si algún valor no cumple PROTOCOLO.md */
    public Config(int codec, int sampleRate, int canales, int muestrasPorBloque, long bufferObjetivoMs) {
        String error = validar(codec, sampleRate, canales, muestrasPorBloque, bufferObjetivoMs);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.canales = canales;
        this.muestrasPorBloque = muestrasPorBloque;
        this.bufferObjetivoMs = bufferObjetivoMs;
    }

    /** IMA ADPCM con bloques de {@link Protocolo#MUESTRAS_POR_BLOQUE} muestras. */
    public static Config imaAdpcm(int sampleRate, int canales, long bufferObjetivoMs) {
        return new Config(Protocolo.CODEC_IMA_ADPCM, sampleRate, canales,
                Protocolo.MUESTRAS_POR_BLOQUE, bufferObjetivoMs);
    }

    /** Bytes de un bloque ADPCM: por canal, 4 de cabecera y una muestra por nibble. */
    public int bytesBloque() {
        return ImaAdpcm.bytesBloque(canales, muestrasPorBloque);
    }

    /** Largo exacto del payload de cada AUDIO con este CONFIG (seq u32 + bloque). */
    public int largoAudio() {
        return 4 + bytesBloque();
    }

    /** Duración de {@code bloques} bloques en ms, truncada (21,333 ms por bloque a 48 kHz). */
    public long msParaBloques(long bloques) {
        return msParaFrames(bloques * muestrasPorBloque);
    }

    /** Duración de {@code frames} frames (una muestra por canal) en ms, truncada. */
    public long msParaFrames(long frames) {
        return frames * 1000L / sampleRate;
    }

    void escribir(DataOutputStream d) throws IOException {
        d.writeByte(codec);
        d.writeInt(sampleRate);
        d.writeByte(canales);
        d.writeShort(muestrasPorBloque);
        d.writeInt((int) bufferObjetivoMs);
    }

    /** @throws ProtocoloException si un valor recibido no cumple PROTOCOLO.md */
    public static Config leer(DataInputStream d) throws IOException {
        int codec = d.readUnsignedByte();
        long sampleRate = d.readInt() & 0xFFFFFFFFL;
        int canales = d.readUnsignedByte();
        int muestrasPorBloque = d.readUnsignedShort();
        long bufferObjetivoMs = d.readInt() & 0xFFFFFFFFL;
        if (sampleRate > Protocolo.TASA_MAX) {
            throw new ProtocoloException("CONFIG inválido: sampleRate " + sampleRate);
        }
        String error = validar(codec, (int) sampleRate, canales, muestrasPorBloque, bufferObjetivoMs);
        if (error != null) {
            throw new ProtocoloException("CONFIG inválido: " + error);
        }
        return new Config(codec, (int) sampleRate, canales, muestrasPorBloque, bufferObjetivoMs);
    }

    private static String validar(int codec, int sampleRate, int canales, int muestrasPorBloque,
            long bufferObjetivoMs) {
        if (codec != Protocolo.CODEC_IMA_ADPCM) {
            return "códec desconocido " + codec;
        }
        if (sampleRate < Protocolo.TASA_MIN || sampleRate > Protocolo.TASA_MAX) {
            return "sampleRate " + sampleRate + " fuera de " + Protocolo.TASA_MIN + "–" + Protocolo.TASA_MAX;
        }
        if (canales < 1 || canales > Protocolo.MAX_CANALES) {
            return "canales " + canales + " fuera de 1–" + Protocolo.MAX_CANALES;
        }
        if (muestrasPorBloque < 2 || muestrasPorBloque > 0xFFFF || muestrasPorBloque % 2 != 0) {
            return "muestrasPorBloque " + muestrasPorBloque + " debe ser par y ≥ 2";
        }
        if (4 + ImaAdpcm.bytesBloque(canales, muestrasPorBloque) > Protocolo.MAX_PAYLOAD) {
            return "un AUDIO de " + muestrasPorBloque + " muestras no cabe en una trama";
        }
        if (bufferObjetivoMs < 0 || bufferObjetivoMs > 0xFFFFFFFFL) {
            return "bufferObjetivoMs fuera de rango: " + bufferObjetivoMs;
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Config)) {
            return false;
        }
        Config c = (Config) o;
        return codec == c.codec && sampleRate == c.sampleRate && canales == c.canales
                && muestrasPorBloque == c.muestrasPorBloque && bufferObjetivoMs == c.bufferObjetivoMs;
    }

    @Override
    public int hashCode() {
        return ((codec * 31 + sampleRate) * 31 + canales) * 31 + muestrasPorBloque;
    }

    @Override
    public String toString() {
        return "Config{códec " + codec + ", " + sampleRate + " Hz, " + canales + " canales, "
                + muestrasPorBloque + " muestras/bloque, objetivo " + bufferObjetivoMs + " ms}";
    }
}
