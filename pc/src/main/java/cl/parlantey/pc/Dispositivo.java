package cl.parlantey.pc;

/** Un dispositivo Bluetooth vinculado con Windows. */
public final class Dispositivo {
    public final String nombre;
    /** Dirección Bluetooth (48 bits). */
    public final long direccion;

    public Dispositivo(String nombre, long direccion) {
        this.nombre = nombre;
        this.direccion = direccion;
    }

    /** "00:11:22:AA:BB:CC". */
    public String textoDireccion() {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 5; i >= 0; i--) {
            sb.append(String.format("%02X", (direccion >> (8 * i)) & 0xFF));
            if (i > 0) {
                sb.append(':');
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return nombre + " (" + textoDireccion() + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dispositivo && ((Dispositivo) o).direccion == direccion;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(direccion);
    }
}
