package cl.parlantey.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Payload de HELLO (R→E): {@code versión u8, apiLevel u16, nombre UTF, maxCanales u8,
 * capacidadBufferMs u32}. La versión es siempre el primer byte, en cualquier versión del
 * protocolo, para poder rechazar una versión distinta sin entender el resto.
 */
public final class Hello {

    public final int version;
    public final int apiLevel;
    public final String nombre;
    public final int maxCanales;
    public final long capacidadBufferMs;

    public Hello(int version, int apiLevel, String nombre, int maxCanales, long capacidadBufferMs) {
        Rangos.u8(version, "versión");
        Rangos.u16(apiLevel, "apiLevel");
        if (nombre == null) {
            throw new IllegalArgumentException("nombre nulo");
        }
        Rangos.u8(maxCanales, "maxCanales");
        Rangos.u32(capacidadBufferMs, "capacidadBufferMs");
        this.version = version;
        this.apiLevel = apiLevel;
        this.nombre = nombre;
        this.maxCanales = maxCanales;
        this.capacidadBufferMs = capacidadBufferMs;
    }

    void escribir(DataOutputStream d) throws IOException {
        d.writeByte(version);
        d.writeShort(apiLevel);
        d.writeUTF(nombre);
        d.writeByte(maxCanales);
        d.writeInt((int) capacidadBufferMs);
    }

    /**
     * Lee un HELLO.
     *
     * @throws VersionIncompatibleException si la versión no es {@link Protocolo#VERSION};
     *         en ese caso el resto del payload no se interpreta
     */
    public static Hello leer(DataInputStream d) throws IOException {
        int version = d.readUnsignedByte();
        if (version != Protocolo.VERSION) {
            throw new VersionIncompatibleException(version);
        }
        int apiLevel = d.readUnsignedShort();
        String nombre = d.readUTF();
        int maxCanales = d.readUnsignedByte();
        long capacidadBufferMs = d.readInt() & 0xFFFFFFFFL;
        return new Hello(version, apiLevel, nombre, maxCanales, capacidadBufferMs);
    }

    @Override
    public String toString() {
        return "Hello{v" + version + ", api " + apiLevel + ", \"" + nombre + "\", "
                + maxCanales + " canales, " + capacidadBufferMs + " ms}";
    }
}
