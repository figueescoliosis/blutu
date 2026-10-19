package cl.parlantey.pc;

import cl.parlantey.core.ColaCaptura;

/** Un tono de 440 Hz a tiempo real, de a 10 ms, como lo entregaría la captura de Windows. */
final class FuenteDePrueba implements FuenteSistema {
    /** Si es true, silenciar también calla la captura (como en algunos PC). */
    volatile boolean silencioCalla;
    volatile boolean silenciada;
    volatile int vecesSilenciada;
    private volatile ColaCaptura cola;
    private volatile Thread hilo;

    @Override
    public synchronized ColaCaptura iniciar() {
        ColaCaptura c = new ColaCaptura(48000, 2, 250);
        cola = c;
        Thread t = new Thread(() -> {
            short[] buf = new short[480 * 2];
            long n = 0;
            long inicio = System.nanoTime();
            long bloques = 0;
            while (cola == c) {
                boolean callar = silencioCalla && silenciada;
                for (int f = 0; f < 480; f++, n++) {
                    short v = callar ? 0 : (short) (8000 * Math.sin(2 * Math.PI * 440 * n / 48000));
                    buf[f * 2] = v;
                    buf[f * 2 + 1] = v;
                }
                c.escribir(buf, 0, 480);
                bloques++;
                long siguiente = inicio + bloques * 10000000L;
                long espera = siguiente - System.nanoTime();
                if (espera > 0) {
                    try {
                        Thread.sleep(espera / 1000000L, (int) (espera % 1000000L));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "captura-prueba");
        t.setDaemon(true);
        hilo = t;
        t.start();
        return c;
    }

    @Override
    public synchronized void detener() {
        ColaCaptura c = cola;
        cola = null;
        if (c != null) {
            c.cerrar();
        }
        silenciada = false;
    }

    @Override
    public void silenciar(boolean si) {
        if (si && !silenciada) {
            vecesSilenciada++;
        }
        silenciada = si;
    }

    @Override
    public boolean silenciado() {
        return silenciada;
    }

    @Override
    public String descripcion() {
        return cola != null ? "48 kHz de prueba, 2 canales" : null;
    }

    @Override
    public String error() {
        return null;
    }
}
