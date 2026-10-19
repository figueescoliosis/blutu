package cl.parlantey.pc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Tubería de bytes acotada entre dos hilos cualesquiera; cerrarla desbloquea a los dos lados. */
final class Tuberia {
    private final byte[] datos;
    private int inicio;
    private int largo;
    private boolean cerrada;

    Tuberia(int capacidad) {
        datos = new byte[capacidad];
    }

    synchronized void cerrar() {
        cerrada = true;
        notifyAll();
    }

    final InputStream entrada = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            synchronized (Tuberia.this) {
                while (largo == 0) {
                    if (cerrada) {
                        throw new IOException("tubería cerrada");
                    }
                    esperar();
                }
                int n = Math.min(len, largo);
                for (int i = 0; i < n; i++) {
                    b[off + i] = datos[(inicio + i) % datos.length];
                }
                inicio = (inicio + n) % datos.length;
                largo -= n;
                Tuberia.this.notifyAll();
                return n;
            }
        }
    };

    final OutputStream salida = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            synchronized (Tuberia.this) {
                for (int i = 0; i < len; i++) {
                    while (largo == datos.length) {
                        if (cerrada) {
                            throw new IOException("tubería cerrada");
                        }
                        esperar();
                    }
                    if (cerrada) {
                        throw new IOException("tubería cerrada");
                    }
                    datos[(inicio + largo) % datos.length] = b[off + i];
                    largo++;
                }
                Tuberia.this.notifyAll();
            }
        }
    };

    private void esperar() throws IOException {
        try {
            wait(100);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }
}
