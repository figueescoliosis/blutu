package cl.parlantey.core;

import java.util.UUID;

/**
 * Constantes del protocolo ParlanteY. La especificación completa está en PROTOCOLO.md
 * (fuente de verdad): cualquier cambio aquí exige actualizar ese archivo, los tests y
 * {@link #VERSION}.
 */
public final class Protocolo {

    private Protocolo() {
    }

    /**
     * Versión del protocolo; viaja como primer byte del payload de HELLO. v2: CONTROL FIN. v3: CONFIG
     * en vivo ({@link #FLAG_EN_VIVO}).
     */
    public static final int VERSION = 3;

    /** UUID v4 del servicio RFCOMM. Generado una sola vez: no cambiar. */
    public static final UUID UUID_SERVICIO = UUID.fromString("c9cc3858-c5e1-46a3-9f9c-f1e94f108feb");

    /** Nombre del registro SDP que publica el receptor. */
    public static final String NOMBRE_SERVICIO = "ParlanteY";

    /** Valor de los dos primeros bytes de cada trama ("PY"). */
    public static final int MAGIC = 0x5059;

    /** magic u16 + tipo u8 + flags u8 + largo u32. */
    public static final int LARGO_CABECERA = 8;

    /** Largo máximo del payload de una trama (64 KiB). */
    public static final int MAX_PAYLOAD = 64 * 1024;

    // Tipos de trama.
    public static final int HELLO = 0x01;
    public static final int CONFIG = 0x02;
    public static final int AUDIO = 0x03;
    public static final int TRACK = 0x04;
    public static final int CONTROL = 0x05;
    public static final int STATE = 0x06;
    public static final int PING = 0x07;
    public static final int PONG = 0x08;
    public static final int TEST_DATA = 0x09;
    public static final int TEST_RESULT = 0x0A;
    public static final int BYE = 0x0B;

    /** Bit 0 de {@code flags} en TEST_DATA: última trama de la prueba. */
    public static final int FLAG_ULTIMA = 0x01;
    /**
     * Bit 0 de {@code flags} en CONFIG (v3): audio en vivo (captura). El receptor compensa la deriva
     * de reloj alrededor de bufferObjetivoMs.
     */
    public static final int FLAG_EN_VIVO = 0x01;

    // Códigos de BYE.
    public static final int BYE_NORMAL = 0;
    public static final int BYE_VERSION = 1;
    public static final int BYE_PROTOCOLO = 2;

    // Comandos de CONTROL.
    public static final int CONTROL_PAUSA = 1;
    public static final int CONTROL_REANUDAR = 2;
    public static final int CONTROL_VACIAR = 3;
    public static final int CONTROL_DETENER = 4;
    /** v2: no viene más audio; el receptor reproduce lo encolado y se detiene sin contar corte. */
    public static final int CONTROL_FIN = 5;

    /** Único códec de CONFIG. */
    public static final int CODEC_IMA_ADPCM = 1;

    /** Muestras por canal en cada bloque ADPCM. */
    public static final int MUESTRAS_POR_BLOQUE = 1024;

    /** Rango de sampleRate que acepta AudioTrack en Android 2.3. */
    public static final int TASA_MIN = 4000;
    public static final int TASA_MAX = 48000;

    public static final int MAX_CANALES = 2;

    /** ultimoSeq de STATE antes de recibir el primer AUDIO (equivale a −1 en u32). */
    public static final long SEQ_NINGUNO = 0xFFFFFFFFL;
}
