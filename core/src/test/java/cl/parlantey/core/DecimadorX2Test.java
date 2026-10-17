package cl.parlantey.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DecimadorX2Test {

    /** Respuesta en dB de la tabla Java a la frecuencia {@code f} (fracción de la tasa de entrada). */
    private static double respuestaDb(double f) {
        int[] h = new int[DecimadorX2.TAPS];
        for (int j = 0; j < DecimadorX2.LADO.length; j++) {
            h[2 * j] = DecimadorX2.LADO[j];
            h[DecimadorX2.TAPS - 1 - 2 * j] = DecimadorX2.LADO[j];
        }
        h[DecimadorX2.TAPS / 2] = DecimadorX2.CENTRO;
        double re = 0;
        double im = 0;
        for (int n = 0; n < h.length; n++) {
            re += h[n] * Math.cos(2 * Math.PI * f * n);
            im += h[n] * Math.sin(2 * Math.PI * f * n);
        }
        return 20 * Math.log10(Math.hypot(re, im) / 32768);
    }

    @Test
    public void tablaDeCoeficientesCumpleElDiseno() {
        int suma = DecimadorX2.CENTRO;
        for (int c : DecimadorX2.LADO) {
            suma += 2 * c;
        }
        assertEquals("ganancia DC exactamente 1 en Q15", 32768, suma);
        for (int i = 0; i <= 400; i++) {
            double f = 0.2 * i / 400;
            assertTrue("rizado en " + f, Math.abs(respuestaDb(f)) <= 0.01);
        }
        for (int i = 0; i <= 400; i++) {
            double f = 0.3 + 0.2 * i / 400;
            assertTrue("atenuación en " + f + ": " + respuestaDb(f), respuestaDb(f) <= -65);
        }
    }

    private static short[] decimar(short[] entrada, int canales) {
        int frames = entrada.length / canales;
        short[] salida = new short[(frames / 2 + 1) * canales];
        int n = new DecimadorX2(canales).procesar(entrada, 0, frames, salida, 0);
        short[] exacto = new short[n * canales];
        System.arraycopy(salida, 0, exacto, 0, exacto.length);
        return exacto;
    }

    @Test
    public void gananciaDcExacta() {
        short[] entrada = new short[400];
        java.util.Arrays.fill(entrada, (short) -12345);
        short[] salida = decimar(entrada, 1);
        assertEquals(200, salida.length);
        for (int i = 30; i < salida.length; i++) { // tras el transitorio de 23 muestras de entrada
            assertEquals(-12345, salida[i]);
        }
    }

    @Test
    public void bandaPasanteYBandaAtenuada() {
        int frames = 8000;
        double pasa = rmsSalida(Senales.seno(frames, 1, 1, 0.1, 0.5));
        double entrada = 0.5 * 32767 / Math.sqrt(2);
        double gananciaDb = 20 * Math.log10(pasa / entrada);
        System.out.printf("Decimador: tono a 0,1 fs → %.3f dB%n", gananciaDb);
        assertTrue("ganancia en banda pasante " + gananciaDb, Math.abs(gananciaDb) <= 0.05);

        double atenuado = rmsSalida(Senales.seno(frames, 1, 1, 0.35, 0.9));
        double atenuacionDb = 20 * Math.log10(atenuado / (0.9 * 32767 / Math.sqrt(2)));
        System.out.printf("Decimador: tono a 0,35 fs → %.1f dB%n", atenuacionDb);
        assertTrue("atenuación " + atenuacionDb, atenuacionDb <= -60);
    }

    private static double rmsSalida(short[] entrada) {
        short[] salida = decimar(entrada, 1);
        double suma = 0;
        int n = 0;
        for (int i = 100; i < salida.length; i++) {
            suma += (double) salida[i] * salida[i];
            n++;
        }
        return Math.sqrt(suma / n);
    }

    @Test
    public void porTrozosIgualQueDeUnaVez() {
        short[] entrada = Senales.sintetica(5001, 2, 31);
        short[] completo = decimar(entrada, 2);

        DecimadorX2 d = new DecimadorX2(2);
        short[] salida = new short[completo.length + 4];
        int escritos = 0;
        int frame = 0;
        int[] trozos = {1, 2, 3, 7, 64, 1, 1, 999, 13};
        for (int t = 0; frame < 5001; t++) {
            int n = Math.min(trozos[t % trozos.length], 5001 - frame);
            escritos += d.procesar(entrada, frame * 2, n, salida, escritos * 2);
            frame += n;
        }
        assertEquals(completo.length / 2, escritos);
        for (int i = 0; i < completo.length; i++) {
            assertEquals("muestra " + i, completo[i], salida[i]);
        }
    }

    @Test
    public void reiniciarOlvidaElHistorial() {
        short[] entrada = Senales.sintetica(1000, 1, 3);
        DecimadorX2 d = new DecimadorX2(1);
        short[] primera = new short[500];
        short[] segunda = new short[500];
        d.procesar(entrada, 0, 1000, primera, 0);
        d.procesar(entrada, 0, 333, segunda, 0); // deja historial y paridad impar
        d.reiniciar();
        assertEquals(500, d.procesar(entrada, 0, 1000, segunda, 0));
        assertArrayEquals(primera, segunda);
    }

    @Test
    public void salidaFijadaPorLaReferencia() {
        short[] estereo = Senales.sintetica(10 * 1024 + 300, 2, 12345);
        short[] salida = decimar(estereo, 2);
        assertEquals(5270 * 2, salida.length);
        assertEquals(0x1AFD6ABEL, Senales.crc(salida, 0, salida.length));
    }

    @Test
    public void etapasSegunLaTasa() {
        assertEquals(0, DecimadorX2.etapasPara(44100));
        assertEquals(0, DecimadorX2.etapasPara(48000));
        assertEquals(1, DecimadorX2.etapasPara(88200));
        assertEquals(1, DecimadorX2.etapasPara(96000));
        assertEquals(2, DecimadorX2.etapasPara(176400));
        assertEquals(2, DecimadorX2.etapasPara(192000));
        assertEquals(3, DecimadorX2.etapasPara(384000));
    }
}
