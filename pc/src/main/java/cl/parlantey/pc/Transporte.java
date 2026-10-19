package cl.parlantey.pc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Una conexión con el receptor (RFCOMM en Windows; tuberías en los tests). */
public interface Transporte {
    InputStream entrada();

    OutputStream salida();

    /** Cierra la conexión; desbloquea lecturas y escrituras en curso. Idempotente; cualquier hilo. */
    void cerrar();

    /** Lista los receptores posibles y abre conexiones con ellos. */
    interface Conector {
        /** Vinculados con este PC. Vacío si no hay Bluetooth. */
        List<Dispositivo> vinculados() throws IOException;

        /** Conecta con el servicio de ParlanteY en {@code d} (bloquea; se corta con {@link Transporte#cerrar}). */
        Transporte conectar(Dispositivo d) throws IOException;

        /** Corta una conexión en curso de {@link #conectar} desde otro hilo. */
        void cancelar();
    }
}
