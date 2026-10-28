package cl.parlantey.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Escribe tramas en un flujo. Cada trama (cabecera + payload) se arma en un buffer
 * preasignado y sale en una sola llamada a {@code write}, así que varios hilos pueden
 * enviar por la misma conexión sin intercalar bytes (todos los métodos son synchronized).
 */
public final class EscritorTramas {

    private final OutputStream salida;
    private final BufferTrama trama = new BufferTrama(Protocolo.LARGO_CABECERA + Protocolo.MAX_PAYLOAD);
    private final DataOutputStream datos = new DataOutputStream(trama);
    private long ultimoSeqAudio = Protocolo.SEQ_NINGUNO;

    public EscritorTramas(OutputStream salida) {
        this.salida = salida;
    }

    public synchronized void hello(Hello hello) throws IOException {
        empezar();
        hello.escribir(datos);
        enviar(Protocolo.HELLO, 0);
    }

    public synchronized void config(Config config) throws IOException {
        empezar();
        config.escribir(datos);
        enviar(Protocolo.CONFIG, config.flags());
    }

    /**
     * AUDIO: {@code seq u32} y el bloque ADPCM {@code bloque[desde, desde + largo)}. Copia el
     * bloque al buffer de la trama: no asigna memoria.
     */
    public synchronized void audio(long seq, byte[] bloque, int desde, int largo) throws IOException {
        bloque(Protocolo.AUDIO, seq, bloque, desde, largo);
        ultimoSeqAudio = seq;
    }

    /** MIC_CONFIG (v4, R→E): el mismo payload que CONFIG. */
    public synchronized void micConfig(Config config) throws IOException {
        empezar();
        config.escribir(datos);
        enviar(Protocolo.MIC_CONFIG, config.flags());
    }

    /** MIC_AUDIO (v4, R→E): como {@link #audio}, sin tocar {@link #ultimoSeqAudio}. */
    public synchronized void micAudio(long seq, byte[] bloque, int desde, int largo) throws IOException {
        bloque(Protocolo.MIC_AUDIO, seq, bloque, desde, largo);
    }

    private void bloque(int tipo, long seq, byte[] bloque, int desde, int largo) throws IOException {
        Rangos.u32(seq, "seq");
        if (largo < 0 || largo > Protocolo.MAX_PAYLOAD - 4) {
            throw new IllegalArgumentException("largo de bloque fuera de rango: " + largo);
        }
        empezar();
        datos.writeInt((int) seq);
        trama.write(bloque, desde, largo);
        enviar(tipo, 0);
    }

    /**
     * seq del último AUDIO enviado por esta conexión, o {@link Protocolo#SEQ_NINGUNO}. El seq
     * sigue de un pipeline al siguiente dentro de la misma conexión.
     */
    public synchronized long ultimoSeqAudio() {
        return ultimoSeqAudio;
    }

    public synchronized void track(Track track) throws IOException {
        empezar();
        track.escribir(datos);
        enviar(Protocolo.TRACK, 0);
    }

    public synchronized void control(int comando) throws IOException {
        Rangos.u8(comando, "comando");
        empezar();
        datos.writeByte(comando);
        enviar(Protocolo.CONTROL, 0);
    }

    /** STATE con primitivos: el receptor lo envía cada 250 ms sin crear objetos. */
    public synchronized void state(long bufferMs, long cortes, long ultimoSeq, boolean reproduciendo)
            throws IOException {
        Rangos.u32(bufferMs, "bufferMs");
        Rangos.u32(cortes, "cortes");
        Rangos.u32(ultimoSeq, "ultimoSeq");
        empezar();
        datos.writeInt((int) bufferMs);
        datos.writeInt((int) cortes);
        datos.writeInt((int) ultimoSeq);
        datos.writeByte(reproduciendo ? 1 : 0);
        enviar(Protocolo.STATE, 0);
    }

    public synchronized void ping(long timestamp) throws IOException {
        empezar();
        datos.writeLong(timestamp);
        enviar(Protocolo.PING, 0);
    }

    public synchronized void pong(long timestamp) throws IOException {
        empezar();
        datos.writeLong(timestamp);
        enviar(Protocolo.PONG, 0);
    }

    /** TEST_DATA con {@code largo} bytes de relleno (ceros). */
    public synchronized void testData(int largo, boolean ultima) throws IOException {
        if (largo < 0 || largo > Protocolo.MAX_PAYLOAD) {
            throw new IllegalArgumentException("largo fuera de rango: " + largo);
        }
        empezar();
        trama.rellenar(largo);
        enviar(Protocolo.TEST_DATA, ultima ? Protocolo.FLAG_ULTIMA : 0);
    }

    public synchronized void testResult(long bytesRecibidos, long duracionMs) throws IOException {
        if (bytesRecibidos < 0 || duracionMs < 0 || duracionMs > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("TEST_RESULT fuera de rango");
        }
        empezar();
        datos.writeLong(bytesRecibidos);
        datos.writeInt((int) duracionMs);
        enviar(Protocolo.TEST_RESULT, 0);
    }

    public synchronized void bye(int codigo) throws IOException {
        Rangos.u8(codigo, "código");
        empezar();
        datos.writeByte(codigo);
        enviar(Protocolo.BYE, 0);
    }

    private void empezar() {
        trama.reiniciar();
    }

    private void enviar(int tipo, int flags) throws IOException {
        int largo = trama.size() - Protocolo.LARGO_CABECERA;
        if (largo > Protocolo.MAX_PAYLOAD) {
            throw new ProtocoloException("payload de " + largo + " bytes supera el máximo");
        }
        byte[] b = trama.arreglo();
        b[0] = (byte) (Protocolo.MAGIC >>> 8);
        b[1] = (byte) Protocolo.MAGIC;
        b[2] = (byte) tipo;
        b[3] = (byte) flags;
        b[4] = (byte) (largo >>> 24);
        b[5] = (byte) (largo >>> 16);
        b[6] = (byte) (largo >>> 8);
        b[7] = (byte) largo;
        salida.write(b, 0, trama.size());
        salida.flush();
    }

    /**
     * {@link ByteArrayOutputStream} que reserva los primeros bytes para la cabecera y
     * expone su arreglo interno, para no copiarlo al enviar.
     */
    private static final class BufferTrama extends ByteArrayOutputStream {

        BufferTrama(int capacidad) {
            super(capacidad);
        }

        byte[] arreglo() {
            return buf;
        }

        void reiniciar() {
            count = Protocolo.LARGO_CABECERA;
        }

        void rellenar(int n) {
            Arrays.fill(buf, count, count + n, (byte) 0);
            count += n;
        }
    }
}
