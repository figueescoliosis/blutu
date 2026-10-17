package cl.parlantey.core;

import java.io.IOException;

/** El otro extremo cerró la sesión con BYE mientras se esperaba otra trama. */
public final class ByeRecibidoException extends IOException {

    private static final long serialVersionUID = 1L;

    public final int codigo;

    public ByeRecibidoException(int codigo) {
        super("el otro extremo envió BYE (código " + codigo + ")");
        this.codigo = codigo;
    }
}
