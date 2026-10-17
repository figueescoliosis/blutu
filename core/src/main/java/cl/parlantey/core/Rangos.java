package cl.parlantey.core;

/** Validación de los enteros sin signo del protocolo. */
final class Rangos {

    private Rangos() {
    }

    static void u8(int valor, String campo) {
        if (valor < 0 || valor > 0xFF) {
            throw new IllegalArgumentException(campo + " fuera de rango: " + valor);
        }
    }

    static void u16(int valor, String campo) {
        if (valor < 0 || valor > 0xFFFF) {
            throw new IllegalArgumentException(campo + " fuera de rango: " + valor);
        }
    }

    static void u32(long valor, String campo) {
        if (valor < 0 || valor > 0xFFFFFFFFL) {
            throw new IllegalArgumentException(campo + " fuera de rango: " + valor);
        }
    }
}
