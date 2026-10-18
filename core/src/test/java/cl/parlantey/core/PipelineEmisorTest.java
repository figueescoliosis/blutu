package cl.parlantey.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class PipelineEmisorTest {

    private static final Track TONO = new Track("Tono de prueba", "440 Hz", 0);

    /** Una trama capturada. */
    private static final class Trama {
        final int tipo;
        final byte[] payload;

        Trama(int tipo, byte[] payload) {
            this.tipo = tipo;
            this.payload = payload;
        }

        long seq() {
            return ((payload[0] & 0xFFL) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8)
                    | (payload[3] & 0xFF);
        }

        Config config() throws IOException {
            return Config.leer(new java.io.DataInputStream(new ByteArrayInputStream(payload)));
        }
    }

    /** Conexión falsa: captura lo escrito y entrega el STATE que se le fije. */
    private static final class Conexion implements PipelineEmisor.Estados {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final EscritorTramas escritor = new EscritorTramas(bytes);
        Estado estado;
        private int leidos;

        @Override
        public Estado ultimo() {
            return estado;
        }

        /** Tramas escritas desde la última llamada. */
        List<Trama> nuevas() throws IOException {
            byte[] todo = bytes.toByteArray();
            LectorTramas lector = new LectorTramas(new ByteArrayInputStream(todo, leidos, todo.length - leidos));
            List<Trama> tramas = new ArrayList<Trama>();
            while (true) {
                int tipo;
                try {
                    tipo = lector.leer();
                } catch (EOFException fin) {
                    break;
                }
                byte[] p = new byte[lector.largo()];
                lector.datos().readFully(p);
                tramas.add(new Trama(tipo, p));
            }
            leidos = todo.length;
            return tramas;
        }
    }

    /** Fuente que entrega una señal fija en trozos de largo al azar (o completos con semilla < 0). */
    private static final class FuenteTrozos implements PipelineEmisor.Fuente {
        private final short[] senal;
        private final int tasa;
        private final int canales;
        private final Random azar;
        private int pos;
        int lecturasVacias;

        FuenteTrozos(short[] senal, int tasa, int canales, long semilla) {
            this.senal = senal;
            this.tasa = tasa;
            this.canales = canales;
            this.azar = semilla < 0 ? null : new Random(semilla);
        }

        @Override
        public int tasa() {
            return tasa;
        }

        @Override
        public int canales() {
            return canales;
        }

        @Override
        public int leer(short[] destino, int desdeFrame, int frames) {
            int quedan = senal.length / canales - pos;
            if (quedan == 0) {
                return PipelineEmisor.FIN_FUENTE;
            }
            int n = Math.min(frames, quedan);
            if (azar != null) {
                if (azar.nextInt(8) == 0) {
                    lecturasVacias++;
                    return 0; // a veces no hay nada todavía
                }
                n = Math.min(n, 1 + azar.nextInt(3000));
            }
            System.arraycopy(senal, pos * canales, destino, desdeFrame * canales, n * canales);
            pos += n;
            return n;
        }
    }

    private static PipelineEmisor tono(int tasa, int canales, Preset preset) {
        return new PipelineEmisor(new FuenteTono(tasa, canales), preset, TONO, PipelineEmisor.OBJETIVO_ARCHIVOS_MS);
    }

    /** Llama a paso hasta que pida esperar; devuelve lo que pidió. */
    private static long hastaEsperar(PipelineEmisor p) throws IOException {
        for (int i = 0; i < 100000; i++) {
            long espera = p.paso(0);
            if (espera != 0) {
                return espera;
            }
        }
        fail("el pipeline nunca pide esperar");
        return 0;
    }

    private static int contar(List<Trama> tramas, int tipo) {
        int n = 0;
        for (Trama t : tramas) {
            if (t.tipo == tipo) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void cadaPresetDaSuFormato() {
        assertFormato(48000, 2, Preset.ALTA, 48000, 2);
        assertFormato(48000, 2, Preset.NORMAL, 48000, 1);
        assertFormato(48000, 2, Preset.SEGURA, 24000, 1);
        assertFormato(44100, 2, Preset.SEGURA, 22050, 1);
        assertFormato(96000, 2, Preset.ALTA, 48000, 2);
        assertFormato(96000, 2, Preset.SEGURA, 24000, 1);
        assertFormato(192000, 2, Preset.SEGURA, 24000, 1);
        assertFormato(44100, 1, Preset.ALTA, 44100, 1); // fuente mono: no se duplica a estéreo
        assertFormato(48000, 6, Preset.ALTA, 48000, 1); // 5.1: downmix a mono
        assertFormato(8000, 1, Preset.SEGURA, 4000, 1);
        assertFormato(6000, 1, Preset.SEGURA, 6000, 1); // 3000 Hz sería menos que el mínimo
    }

    private static void assertFormato(int tasa, int canales, Preset preset, int tasaSalida, int canalesSalida) {
        Config c = tono(tasa, canales, preset).config();
        String que = tasa + " Hz × " + canales + " con " + preset;
        assertEquals(que, tasaSalida, c.sampleRate);
        assertEquals(que, canalesSalida, c.canales);
        assertEquals(que, tasaSalida, preset.tasaSalida(tasa));
        assertEquals(1024, c.muestrasPorBloque);
        assertEquals(2000, c.bufferObjetivoMs);
    }

    @Test
    public void empiezaConConfigYTrackYSeqCero() throws IOException {
        PipelineEmisor p = tono(48000, 2, Preset.ALTA);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        assertEquals(0, p.paso(0));
        assertEquals(0, p.paso(0));
        List<Trama> t = con.nuevas();
        assertEquals(4, t.size());
        assertEquals(Protocolo.CONFIG, t.get(0).tipo);
        assertEquals(p.config(), t.get(0).config());
        assertEquals(Protocolo.TRACK, t.get(1).tipo);
        assertEquals(Protocolo.AUDIO, t.get(2).tipo);
        assertEquals(0, t.get(2).seq());
        assertEquals(p.config().largoAudio(), t.get(2).payload.length);
        assertEquals(1, t.get(3).seq());
    }

    @Test
    public void sinStateEnviaSoloHastaElObjetivo() throws IOException {
        PipelineEmisor p = tono(48000, 2, Preset.ALTA);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        assertEquals(PipelineEmisor.ESPERA_LLENO_MS, hastaEsperar(p));
        // 94 bloques de 21,333 ms = 2005 ms: el primer nivel ≥ 2000.
        assertEquals(94, contar(con.nuevas(), Protocolo.AUDIO));
        assertEquals(2005, p.nivelEstimadoMs());
        assertEquals(93, p.seqEnviado());
        // Sin STATE nuevo no envía nada más, por más pasos que dé.
        for (int i = 0; i < 100; i++) {
            assertEquals(PipelineEmisor.ESPERA_LLENO_MS, p.paso(0));
        }
        assertEquals(0, contar(con.nuevas(), Protocolo.AUDIO));
    }

    @Test
    public void conStateRellenaLoQueSeReprodujo() throws IOException {
        PipelineEmisor p = tono(48000, 2, Preset.ALTA);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        hastaEsperar(p);
        con.nuevas();
        con.estado = new Estado(1000, 0, 93, true); // todo recibido; queda 1 s en el receptor
        hastaEsperar(p);
        // 1000 + msParaBloques(47) = 1000 + 1002 ≥ 2000.
        assertEquals(47, contar(con.nuevas(), Protocolo.AUDIO));
        assertEquals(140, p.seqEnviado());

        con.estado = new Estado(1500, 0, 120, true); // 20 en vuelo = 426 ms → 1926 ms
        hastaEsperar(p);
        assertEquals(4, contar(con.nuevas(), Protocolo.AUDIO)); // 1500 + 24 × 21,33 = 2012
        assertTrue(p.nivelEstimadoMs() <= 2000 + 22);
    }

    @Test
    public void stateDeOtraConexionSeIgnora() throws IOException {
        PipelineEmisor p = tono(48000, 2, Preset.NORMAL);
        Conexion vieja = new Conexion();
        p.conectar(vieja.escritor, vieja);
        for (int i = 0; i < 3; i++) {
            vieja.estado = new Estado(500, 0, p.seqEnviado(), true);
            hastaEsperar(p);
        }
        assertTrue(p.seqEnviado() > 150);
        p.desconectar();

        Conexion nueva = new Conexion();
        nueva.estado = new Estado(0, 0, 200, true); // como si fuera de la conexión anterior
        p.conectar(nueva.escritor, nueva);
        hastaEsperar(p);
        List<Trama> t = nueva.nuevas();
        assertEquals("CONFIG de nuevo al reconectar", Protocolo.CONFIG, t.get(0).tipo);
        assertEquals(Protocolo.TRACK, t.get(1).tipo);
        assertEquals("el seq vuelve a 0", 0, t.get(2).seq());
        assertEquals("el STATE ajeno no hace enviar de más", 94, contar(t, Protocolo.AUDIO));

        // Un STATE de antes del primer AUDIO de esta conexión tampoco cuenta.
        nueva.estado = new Estado(0, 0, Protocolo.SEQ_NINGUNO, false);
        hastaEsperar(p);
        assertEquals(0, contar(nueva.nuevas(), Protocolo.AUDIO));
    }

    @Test
    public void trasVaciarIgnoraElStateViejo() throws IOException {
        PipelineEmisor p = tono(48000, 2, Preset.ALTA);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        hastaEsperar(p);
        con.estado = new Estado(1900, 0, 93, true);
        con.nuevas();
        p.pedirVaciar();
        hastaEsperar(p);
        List<Trama> t = con.nuevas();
        assertEquals(Protocolo.CONTROL, t.get(0).tipo);
        assertEquals(Protocolo.CONTROL_VACIAR, t.get(0).payload[0]);
        assertEquals("el primer bloque tras VACIAR sigue el seq", 94, t.get(1).seq());
        assertEquals("el STATE de antes del VACIAR no frena", 94, contar(t, Protocolo.AUDIO));

        con.estado = new Estado(1500, 0, 150, true); // ya posterior al corte (seq ≥ 94)
        assertEquals(PipelineEmisor.ESPERA_LLENO_MS, p.paso(0));
        assertEquals(1500 + p.config().msParaBloques(187 - 150), p.nivelEstimadoMs());
    }

    @Test
    public void detenerEnviaDetenerYTermina() throws IOException {
        PipelineEmisor p = tono(48000, 2, Preset.ALTA);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        hastaEsperar(p);
        con.nuevas();
        p.pedirDetener();
        assertEquals(PipelineEmisor.FIN, p.paso(0));
        assertEquals(PipelineEmisor.FIN, p.paso(0));
        List<Trama> t = con.nuevas();
        assertEquals(1, t.size());
        assertEquals(Protocolo.CONTROL, t.get(0).tipo);
        assertEquals(Protocolo.CONTROL_DETENER, t.get(0).payload[0]);
        assertTrue(p.terminado());
    }

    @Test
    public void fuenteFinitaRellenaElUltimoBloque() throws IOException {
        short[] senal = Senales.sintetica(2560, 1, 5); // 2,5 bloques
        PipelineEmisor p = new PipelineEmisor(new FuenteTrozos(senal, 44100, 1, -1), Preset.NORMAL, null, 2000);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        assertEquals(PipelineEmisor.FIN, hastaEsperar(p));
        List<Trama> t = con.nuevas();
        assertEquals(Protocolo.CONFIG, t.get(0).tipo);
        assertEquals("sin TRACK si es null", Protocolo.AUDIO, t.get(1).tipo);
        assertEquals(3, contar(t, Protocolo.AUDIO));
        // El último bloque: 512 muestras reales y el resto, ceros codificados.
        CodificadorIma cod = new CodificadorIma(1, 1024);
        byte[] esperado = new byte[cod.bytesBloque()];
        cod.codificarBloque(senal, 0, 1024, esperado, 0);
        cod.codificarBloque(senal, 1024, 1024, esperado, 0);
        cod.codificarBloque(senal, 2048, 512, esperado, 0);
        byte[] ultimo = java.util.Arrays.copyOfRange(t.get(3).payload, 4, t.get(3).payload.length);
        assertArrayEquals(esperado, ultimo);
    }

    @Test
    public void sinDatosEsperaSinEnviar() throws IOException {
        PipelineEmisor.Fuente vacia = new PipelineEmisor.Fuente() {
            @Override
            public int tasa() {
                return 48000;
            }

            @Override
            public int canales() {
                return 2;
            }

            @Override
            public int leer(short[] destino, int desdeFrame, int frames) {
                return 0;
            }
        };
        PipelineEmisor p = new PipelineEmisor(vacia, Preset.ALTA, TONO, 500);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        assertEquals(PipelineEmisor.ESPERA_SIN_DATOS_MS, p.paso(0));
        assertEquals(0, contar(con.nuevas(), Protocolo.AUDIO));
    }

    /** Trozos de cualquier largo (también impares) dan exactamente los mismos bloques. */
    @Test
    public void trozosIrregularesDanLosMismosBloques() throws IOException {
        int[][] casos = {{48000, Preset.ALTA.ordinal()}, {48000, Preset.SEGURA.ordinal()},
            {96000, Preset.NORMAL.ordinal()}, {192000, Preset.SEGURA.ordinal()}};
        for (int[] caso : casos) {
            int tasa = caso[0];
            Preset preset = Preset.values()[caso[1]];
            short[] senal = Senales.sintetica(tasa * 2, 2, 9); // 2 s
            List<byte[]> completos = bloques(new FuenteTrozos(senal, tasa, 2, -1), preset);
            FuenteTrozos irregular = new FuenteTrozos(senal, tasa, 2, 3);
            List<byte[]> trozos = bloques(irregular, preset);
            assertTrue(irregular.lecturasVacias > 0);
            assertEquals(completos.size(), trozos.size());
            for (int i = 0; i < completos.size(); i++) {
                assertArrayEquals(tasa + " " + preset + ", bloque " + i, completos.get(i), trozos.get(i));
            }
        }
    }

    private static List<byte[]> bloques(PipelineEmisor.Fuente fuente, Preset preset) throws IOException {
        PipelineEmisor p = new PipelineEmisor(fuente, preset, null, 1000000);
        Conexion con = new Conexion();
        p.conectar(con.escritor, con);
        for (int i = 0; i < 1000000 && p.paso(0) != PipelineEmisor.FIN; i++) {
            // sigue
        }
        List<byte[]> bloques = new ArrayList<byte[]>();
        for (Trama t : con.nuevas()) {
            if (t.tipo == Protocolo.AUDIO) {
                bloques.add(t.payload);
            }
        }
        return bloques;
    }

    /** El tono decodificado es un seno limpio de 440 Hz con todos los presets. */
    @Test
    public void tonoDecodificadoTieneBuenaSnr() throws IOException {
        for (Preset preset : Preset.values()) {
            PipelineEmisor p = tono(48000, 2, preset);
            Config c = p.config();
            Conexion con = new Conexion();
            p.conectar(con.escritor, con);
            hastaEsperar(p);
            List<Trama> t = con.nuevas();
            int bloques = contar(t, Protocolo.AUDIO);
            short[] pcm = new short[bloques * 1024 * c.canales];
            int k = 0;
            for (Trama tr : t) {
                if (tr.tipo == Protocolo.AUDIO) {
                    ImaAdpcm.decodificarBloque(tr.payload, 4, c.canales, 1024, pcm, k * 1024 * c.canales);
                    k++;
                }
            }
            int desde = 2048; // sin el arranque del códec y del filtro
            int n = bloques * 1024 - desde;
            double snr = Senales.snrTono(pcm, desde * c.canales, n, c.canales, c.sampleRate, 440);
            assertTrue(preset + ": SNR " + snr, snr >= 20);
            if (c.canales == 2) {
                assertTrue(Senales.snrTono(pcm, desde * 2 + 1, n, 2, c.sampleRate, 440) >= 20);
            }
        }
    }
}
