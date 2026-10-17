package cl.parlantey.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class DownmixTest {

    private static short[] mono(int canales, int... muestras) {
        short[] entrada = new short[muestras.length];
        for (int i = 0; i < muestras.length; i++) {
            entrada[i] = (short) muestras[i];
        }
        short[] salida = new short[muestras.length / canales];
        Downmix.aMono(entrada, 0, salida.length, canales, salida, 0);
        return salida;
    }

    @Test
    public void promedioConRedondeoAlejadoDeCero() {
        assertArrayEquals(new short[] {150, 2, -2, 0, 1, -1}, mono(2, 100, 200, 1, 2, -1, -2, 1, -1, 0, 1, 0, -1));
        assertArrayEquals(new short[] {7}, mono(1, 7));
        assertArrayEquals(new short[] {1, 2}, mono(3, 1, 1, 0, 2, 2, 1)); // 0,667 → 1; 1,667 → 2
    }

    @Test
    public void extremosNoDesbordan() {
        assertArrayEquals(new short[] {32767, -32768, 0},
                mono(2, 32767, 32767, -32768, -32768, 32767, -32767));
        assertArrayEquals(new short[] {-32768}, mono(6, -32768, -32768, -32768, -32768, -32768, -32768));
    }

    @Test
    public void deUnoASeisCanales() {
        for (int canales = 1; canales <= 6; canales++) {
            short[] entrada = new short[canales];
            for (int c = 0; c < canales; c++) {
                entrada[c] = (short) (1000 * (c + 1));
            }
            short[] salida = new short[1];
            Downmix.aMono(entrada, 0, 1, canales, salida, 0);
            int esperado = Math.round(1000f * (canales + 1) / 2); // promedio de 1000..1000·n
            assertEquals("canales " + canales, esperado, salida[0]);
        }
    }

    @Test
    public void enElMismoArreglo() {
        short[] pcm = Senales.sintetica(500, 2, 4);
        short[] esperado = new short[500];
        Downmix.aMono(pcm, 0, 500, 2, esperado, 0);
        Downmix.aMono(pcm, 0, 500, 2, pcm, 0);
        for (int i = 0; i < 500; i++) {
            assertEquals(esperado[i], pcm[i]);
        }
    }

    @Test
    public void canalesFueraDeRango() {
        for (int canales : new int[] {0, 9}) {
            try {
                Downmix.aMono(new short[18], 0, 1, canales, new short[2], 0);
                fail("debió rechazar " + canales + " canales");
            } catch (IllegalArgumentException esperada) {
                // ok
            }
        }
    }

    @Test
    public void salidaFijadaPorLaReferencia() {
        short[] seis = Senales.sintetica(2000, 6, 99);
        short[] salida = new short[2000];
        Downmix.aMono(seis, 0, 2000, 6, salida, 0);
        assertEquals(0x8CE179F2L, Senales.crc(salida, 0, 2000));

        short[] estereo = Senales.sintetica(10 * 1024 + 300, 2, 12345);
        short[] mono = new short[10 * 1024 + 300];
        Downmix.aMono(estereo, 0, mono.length, 2, mono, 0);
        assertEquals(0xDD677198L, Senales.crc(mono, 0, mono.length));
    }
}
