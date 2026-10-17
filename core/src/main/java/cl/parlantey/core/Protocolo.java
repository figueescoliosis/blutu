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

    /** Versión del protocolo; viaja como primer byte del payload de HELLO. */
    public static final int VERSION = 1;

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

    // Tipos de trama implementados en la Fase 0. El resto de la tabla está en PROTOCOLO.md.
    public static final int HELLO = 0x01;
    public static final int PING = 0x07;
    public static final int PONG = 0x08;
    public static final int TEST_DATA = 0x09;
    public static final int TEST_RESULT = 0x0A;
    public static final int BYE = 0x0B;

    /** Bit 0 de {@code flags} en TEST_DATA: última trama de la prueba. */
    public static final int FLAG_ULTIMA = 0x01;

    // Códigos de BYE.
    public static final int BYE_NORMAL = 0;
    public static final int BYE_VERSION = 1;
    public static final int BYE_PROTOCOLO = 2;
}
