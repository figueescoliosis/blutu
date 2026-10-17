package cl.parlantey.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ImaAdpcmTest {

    private static final int MPB = Protocolo.MUESTRAS_POR_BLOQUE;

    /** Codifica {@code frames} frames en bloques; el último se completa con silencio. */
    private static byte[] codificar(short[] pcm, int frames, int canales) {
        CodificadorIma codificador = new CodificadorIma(canales, MPB);
        int bloques = (frames + MPB - 1) / MPB;
        int largo = codificador.bytesBloque();
        byte[] flujo = new byte[bloques * largo];
        for (int b = 0; b < bloques; b++) {
            int validas = Math.min(MPB, frames - b * MPB);
            codificador.codificarBloque(pcm, b * MPB * canales, validas, flujo, b * largo);
        }
        return flujo;
    }

    /** Decodifica cada bloque por separado, desde su cabecera. */
    private static short[] decodificarAislado(byte[] flujo, int canales) {
        int largo = ImaAdpcm.bytesBloque(canales, MPB);
        int bloques = flujo.length / largo;
        short[] pcm = new short[bloques * MPB * canales];
        for (int b = 0; b < bloques; b++) {
            ImaAdpcm.decodificarBloque(flujo, b * largo, canales, MPB, pcm, b * MPB * canales);
        }
        return pcm;
    }

    // --- Criterio ③: SNR ≥ 20 dB sobre un seno de 1 kHz a −6 dBFS y 44,1 kHz.

    @Test
    public void snrSenoMono() {
        verificarSnr(1);
    }

    @Test
    public void snrSenoEstereo() {
        verificarSnr(2);
    }

    private static void verificarSnr(int canales) {
        int frames = 44100; // 1 s: 43 bloques completos y uno parcial
        short[] x = Senales.seno(frames, canales, 44100, 1000, 0.5);
        short[] y = decodificarAislado(codificar(x, frames, canales), canales);
        for (int c = 0; c < canales; c++) {
            double snr = Senales.snr(x, y, frames, canales, c);
            System.out.printf("SNR IMA ADPCM, %d canal(es), canal %d: %.1f dB%n", canales, c, snr);
            assertTrue("SNR " + snr + " dB < 20 dB", snr >= 20);
        }
    }

    // --- Criterio ④: un bloque aislado se decodifica igual que en secuencia.

    @Test
    public void bloqueAisladoIgualQueEnSecuencia() {
        verificarAisladoIgualQueSecuencia(Senales.sintetica(12 * MPB, 1, 4243), 12 * MPB, 1);
        verificarAisladoIgualQueSecuencia(Senales.sintetica(12 * MPB, 2, 4244), 12 * MPB, 2);
        // Señales a escala completa: llevan el índice a 88 y saturan el predictor.
        verificarAisladoIgualQueSecuencia(Senales.cuadrada(4 * MPB, 50), 4 * MPB, 1);
        verificarAisladoIgualQueSecuencia(Senales.ruidoCompleto(3 * MPB, 2, 2024), 3 * MPB, 2);
    }

    private static void verificarAisladoIgualQueSecuencia(short[] x, int frames, int canales) {
        byte[] flujo = codificar(x, frames, canales);
        int largo = ImaAdpcm.bytesBloque(canales, MPB);
        int bloques = flujo.length / largo;

        // En secuencia: el estado viene del bloque anterior; solo se lee la cabecera del primero.
        short[] secuencia = new short[bloques * MPB * canales];
        int[] estado = new int[canales];
        for (int c = 0; c < canales; c++) {
            estado[c] = (predictorCabecera(flujo, 0, c) << 8) | indiceCabecera(flujo, 0, c);
        }
        for (int b = 0; b < bloques; b++) {
            int base = b * largo;
            for (int c = 0; c < canales; c++) {
                assertEquals("la cabecera del bloque " + b + " es el estado arrastrado",
                        ImaAdpcm.predictorDe(estado[c]), predictorCabecera(flujo, base, c));
                assertEquals(ImaAdpcm.indiceDe(estado[c]), indiceCabecera(flujo, base, c));
                assertEquals("byte reservado", 0, flujo[base + ImaAdpcm.CABECERA_CANAL * c + 3]);
                int datos = base + ImaAdpcm.CABECERA_CANAL * canales + c * MPB / 2;
                estado[c] = ImaAdpcm.decodificarCanal(flujo, datos, MPB, ImaAdpcm.predictorDe(estado[c]),
                        ImaAdpcm.indiceDe(estado[c]), secuencia, b * MPB * canales + c, canales);
            }
        }

        // Cada bloque solo, en un arreglo propio.
        for (int b = 0; b < bloques; b++) {
            short[] aislado = new short[MPB * canales];
            ImaAdpcm.decodificarBloque(flujo, b * largo, canales, MPB, aislado, 0);
            for (int i = 0; i < aislado.length; i++) {
                assertEquals("bloque " + b + ", muestra " + i, secuencia[b * MPB * canales + i], aislado[i]);
            }
        }
    }

    private static int predictorCabecera(byte[] flujo, int bloque, int canal) {
        int h = bloque + ImaAdpcm.CABECERA_CANAL * canal;
        return (short) (((flujo[h] & 0xFF) << 8) | (flujo[h + 1] & 0xFF));
    }

    private static int indiceCabecera(byte[] flujo, int bloque, int canal) {
        return flujo[bloque + ImaAdpcm.CABECERA_CANAL * canal + 2] & 0xFF;
    }

    // --- Criterio ⑤: salida determinista, fijada con herramientas/referencia_core.py.

    @Test
    public void codificacionDeterministaYFijadaPorLaReferencia() {
        short[] estereo = Senales.sintetica(10 * MPB + 300, 2, 12345);
        byte[] flujo = codificar(estereo, 10 * MPB + 300, 2);
        assertEquals(11 * 1032, flujo.length);
        assertArrayEquals("codificar dos veces da lo mismo", flujo, codificar(estereo, 10 * MPB + 300, 2));
        assertEquals("CRC del flujo estéreo", 0x6E6601B3L, Senales.crc(flujo));

        short[] decodificado = decodificarAislado(flujo, 2);
        assertEquals("CRC del PCM decodificado", 0x446B3B75L, Senales.crc(decodificado, 0, decodificado.length));

        short[] mono = Senales.sintetica(3 * MPB + 1, 1, 777);
        assertEquals("CRC del flujo mono", 0x0FFB2FF5L, Senales.crc(codificar(mono, 3 * MPB + 1, 1)));
    }

    /** Con el índice en 88 y el predictor saturado, codificador y decodificador siguen a la referencia. */
    @Test
    public void escalaCompletaFijadaPorLaReferencia() {
        byte[] cuadrada = codificar(Senales.cuadrada(4 * MPB, 50), 4 * MPB, 1);
        assertEquals("CRC onda cuadrada", 0x6800C699L, Senales.crc(cuadrada));
        short[] pcm = decodificarAislado(cuadrada, 1);
        assertEquals("CRC onda cuadrada decodificada", 0x983BF95EL, Senales.crc(pcm, 0, pcm.length));

        byte[] ruido = codificar(Senales.ruidoCompleto(3 * MPB, 2, 2024), 3 * MPB, 2);
        assertEquals("CRC ruido a escala completa", 0x088458B6L, Senales.crc(ruido));
        pcm = decodificarAislado(ruido, 2);
        assertEquals("CRC ruido decodificado", 0xD51C53EFL, Senales.crc(pcm, 0, pcm.length));
    }

    // --- Casos borde y vectores calculados a mano.

    @Test
    public void vectoresCalculadosAMano() {
        // Desde (0, 0), el nibble 7 suma paso + paso/2 + paso/4 + paso/8 y sube el índice 8.
        byte[] datos = {0x77, 0x77};
        short[] pcm = new short[4];
        int estado = ImaAdpcm.decodificarCanal(datos, 0, 4, 0, 0, pcm, 0, 1);
        assertArrayEquals(new short[] {11, 41, 104, 240}, pcm);
        assertEquals(240, ImaAdpcm.predictorDe(estado));
        assertEquals(32, ImaAdpcm.indiceDe(estado));

        // Nibble 8 (signo sin magnitud) desde índice 0: el predictor no cambia y el índice no baja de 0.
        estado = ImaAdpcm.decodificarCanal(new byte[] {(byte) 0x88}, 0, 2, -5, 0, pcm, 0, 1);
        assertEquals(-5, pcm[0]);
        assertEquals(-5, pcm[1]);
        assertEquals(0, ImaAdpcm.indiceDe(estado));
    }

    @Test
    public void predictorEIndiceSaturan() {
        short[] pcm = new short[2];
        int estado = ImaAdpcm.decodificarCanal(new byte[] {0x77}, 0, 2, 32000, 88, pcm, 0, 1);
        assertEquals(32767, pcm[0]);
        assertEquals(32767, ImaAdpcm.predictorDe(estado));
        assertEquals(88, ImaAdpcm.indiceDe(estado));

        estado = ImaAdpcm.decodificarCanal(new byte[] {(byte) 0xFF}, 0, 2, -32000, 88, pcm, 0, 1);
        assertEquals(-32768, pcm[1]);
        assertEquals(-32768, ImaAdpcm.predictorDe(estado));
    }

    @Test
    public void ondaCuadradaAEscalaCompleta() {
        int frames = 4 * MPB;
        short[] x = new short[frames];
        for (int i = 0; i < frames; i++) {
            x[i] = (short) ((i / 50) % 2 == 0 ? 32767 : -32768);
        }
        short[] y = decodificarAislado(codificar(x, frames, 1), 1);
        // Tras la primera transición el códec sigue la onda (no se queda pegado).
        assertTrue(y[49] > 20000 && y[99] < -20000);
    }

    @Test
    public void silencioQuedaEnCero() {
        byte[] flujo = codificar(new short[2 * MPB * 2], 2 * MPB, 2);
        for (short v : decodificarAislado(flujo, 2)) {
            assertEquals(0, v);
        }
    }

    @Test
    public void codificadorEscribeTodoElBloque() {
        // Aunque el destino traiga basura, el bloque queda igual (incluido el byte reservado en 0).
        short[] x = Senales.sintetica(MPB, 2, 6);
        byte[] limpio = new byte[ImaAdpcm.bytesBloque(2, MPB)];
        byte[] sucio = new byte[limpio.length];
        java.util.Arrays.fill(sucio, (byte) 0xFF);
        new CodificadorIma(2, MPB).codificarBloque(x, 0, MPB, limpio, 0);
        new CodificadorIma(2, MPB).codificarBloque(x, 0, MPB, sucio, 0);
        assertArrayEquals(limpio, sucio);
        assertEquals(0, sucio[3]);
        assertEquals(0, sucio[ImaAdpcm.CABECERA_CANAL + 3]);
    }

    @Test
    public void indiceInvalidoEnCabeceraSeLimita() {
        byte[] bloque = new byte[ImaAdpcm.bytesBloque(1, MPB)];
        bloque[2] = (byte) 200; // índice > 88: se trata como 88
        short[] pcm = new short[MPB];
        ImaAdpcm.decodificarBloque(bloque, 0, 1, MPB, pcm, 0);

        byte[] limitado = bloque.clone();
        limitado[2] = 88;
        short[] esperado = new short[MPB];
        ImaAdpcm.decodificarBloque(limitado, 0, 1, MPB, esperado, 0);
        assertArrayEquals(esperado, pcm);
    }

    @Test
    public void ultimoBloqueSeCompletaConSilencio() {
        short[] x = Senales.sintetica(MPB, 1, 5);
        CodificadorIma a = new CodificadorIma(1, MPB);
        CodificadorIma b = new CodificadorIma(1, MPB);
        byte[] conRelleno = new byte[a.bytesBloque()];
        byte[] conCeros = new byte[b.bytesBloque()];

        a.codificarBloque(x, 0, 100, conRelleno, 0);
        short[] ceros = x.clone();
        for (int i = 100; i < MPB; i++) {
            ceros[i] = 0;
        }
        b.codificarBloque(ceros, 0, MPB, conCeros, 0);
        assertArrayEquals(conCeros, conRelleno);
    }

    @Test
    public void reiniciarVuelveAlEstadoInicial() {
        short[] x = Senales.sintetica(MPB, 2, 8);
        CodificadorIma codificador = new CodificadorIma(2, MPB);
        byte[] primero = new byte[codificador.bytesBloque()];
        byte[] otro = new byte[codificador.bytesBloque()];
        codificador.codificarBloque(x, 0, MPB, primero, 0);
        codificador.codificarBloque(x, 0, MPB, otro, 0);
        codificador.reiniciar();
        codificador.codificarBloque(x, 0, MPB, otro, 0);
        assertArrayEquals(primero, otro);
    }

    @Test
    public void tamanosDeBloque() {
        assertEquals(516, ImaAdpcm.bytesBloque(1, 1024));
        assertEquals(1032, ImaAdpcm.bytesBloque(2, 1024));
        assertEquals(1032, new CodificadorIma(2, 1024).bytesBloque());
    }

    /**
     * Referencia de rendimiento. Los tests de core corren con -Xint (intérprete), así que esta
     * cifra es conservadora; la medición real en el Galaxy Y va en la Fase 2.
     */
    @Test
    public void rendimientoDeReferencia() {
        short[] x = Senales.sintetica(48 * MPB, 2, 1);
        byte[] flujo = codificar(x, 48 * MPB, 2);
        short[] pcm = new short[MPB * 2];
        int largo = ImaAdpcm.bytesBloque(2, MPB);
        for (int r = 0; r < 20; r++) { // calentamiento
            for (int b = 0; b < 48; b++) {
                ImaAdpcm.decodificarBloque(flujo, b * largo, 2, MPB, pcm, 0);
            }
        }
        long t = System.nanoTime();
        int bloques = 0;
        for (int r = 0; r < 50; r++) {
            for (int b = 0; b < 48; b++) {
                ImaAdpcm.decodificarBloque(flujo, b * largo, 2, MPB, pcm, 0);
                bloques++;
            }
        }
        double segundos = (System.nanoTime() - t) / 1e9;
        double audio = bloques * MPB / 48000.0;
        System.out.printf("Decodificador (JVM, intérprete): %.0f× tiempo real a 48 kHz estéreo%n", audio / segundos);
    }
}
