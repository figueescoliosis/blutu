package cl.parlantey.core;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Payload de STATE (R→E): {@code bufferMs u32, cortes u32, ultimoSeq u32, reproduciendo u8}.
 * Lo lee el emisor; el receptor lo escribe con {@link EscritorTramas#state} sin crear objetos.
 */
public final class Estado {

    public final long bufferMs;
    public final long cortes;
    /** seq del último AUDIO recibido, o {@link Protocolo#SEQ_NINGUNO} si todavía no llega ninguno. */
    public final long ultimoSeq;
    public final boolean reproduciendo;

    public Estado(long bufferMs, long cortes, long ultimoSeq, boolean reproduciendo) {
        Rangos.u32(bufferMs, "bufferMs");
        Rangos.u32(cortes, "cortes");
        Rangos.u32(ultimoSeq, "ultimoSeq");
        this.bufferMs = bufferMs;
        this.cortes = cortes;
        this.ultimoSeq = ultimoSeq;
        this.reproduciendo = reproduciendo;
    }

    public static Estado leer(DataInputStream d) throws IOException {
        long bufferMs = d.readInt() & 0xFFFFFFFFL;
        long cortes = d.readInt() & 0xFFFFFFFFL;
        long ultimoSeq = d.readInt() & 0xFFFFFFFFL;
        boolean reproduciendo = d.readUnsignedByte() != 0;
        return new Estado(bufferMs, cortes, ultimoSeq, reproduciendo);
    }

    @Override
    public String toString() {
        return "Estado{" + bufferMs + " ms, " + cortes + " cortes, último seq " + ultimoSeq
                + (reproduciendo ? ", reproduciendo}" : ", detenido}");
    }
}
