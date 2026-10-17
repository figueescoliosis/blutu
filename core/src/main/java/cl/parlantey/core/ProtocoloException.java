package cl.parlantey.core;

import java.io.IOException;

/** Trama o mensaje inválido. Quien la recibe debe cerrar la conexión. */
public class ProtocoloException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProtocoloException(String mensaje) {
        super(mensaje);
    }
}
