package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ColaCapturaTest {

    private static short[] rampa(int desde, int frames, int canales) {
        short[] s = new short[frames * canales];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < canales; c++) {
                s[f * canales + c] = (short) (desde + f);
            }
        }
        return s;
    }

    @Test
    public void entregaEnOrdenYSinBloquear() {
        ColaCaptura c = new ColaCaptura(48000, 2, 250);
        short[] destino = new short[2000];
        assertEquals("vacía: 0, no bloquea", 0, c.leer(destino, 0, 100));
        c.escribir(rampa(0, 480, 2), 0, 480);
        c.escribir(rampa(480, 480, 2), 0, 480);
        assertEquals(20, c.msPendientes());
        assertEquals(300, c.leer(destino, 0, 300));
        assertEquals(660, c.leer(destino, 300, 1000));
        for (int f = 0; f < 960; f++) {
            assertEquals(f, destino[f * 2]);
            assertEquals(f, destino[f * 2 + 1]);
        }
        assertEquals(0, c.msDescartados());
    }

    /** Llena: se descarta lo más antiguo, se conserva lo más reciente y da la vuelta bien. */
    @Test
    public void llenaDescartaLoMasAntiguo() {
        ColaCaptura c = new ColaCaptura(1000, 1, 100); // 100 frames
        short[] destino = new short[200];
        for (int i = 0; i < 7; i++) {
            c.escribir(rampa(i * 30, 30, 1), 0, 30); // 210 frames
        }
        assertEquals(100, c.msPendientes());
        assertEquals(110, c.msDescartados());
        assertEquals(100, c.leer(destino, 0, 200));
        for (int f = 0; f < 100; f++) {
            assertEquals("lo más reciente", 110 + f, destino[f]);
        }
        // Más que la capacidad de una vez: quedan los últimos.
        c.escribir(rampa(1000, 250, 1), 0, 250);
        assertEquals(100, c.leer(destino, 0, 200));
        assertEquals(1150, destino[0]);
        assertEquals(1249, destino[99]);
    }

    @Test
    public void vaciarCerrarYPico() {
        ColaCaptura c = new ColaCaptura(1000, 1, 100);
        short[] s = {0, -5000, 300};
        c.escribir(s, 0, 3);
        assertEquals(5000, c.picoYReiniciar());
        assertEquals(0, c.picoYReiniciar());
        c.vaciar();
        short[] destino = new short[10];
        assertEquals(0, c.leer(destino, 0, 10));
        c.escribir(s, 0, 3);
        c.cerrar();
        assertEquals("lo que quedaba sale igual", 3, c.leer(destino, 0, 10));
        assertEquals(Fuente.FIN, c.leer(destino, 0, 10));
        assertTrue(c.framesEscritos() >= 6);
    }
}
