package cl.parlantey.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class ColaBloquesTest {

    private static final Config CONFIG = Config.imaAdpcm(48000, 2, 2000);

    @Test
    public void entregaEnOrdenYRegistraElUltimoSeq() {
        ColaBloques cola = new ColaBloques(8, 1032);
        assertEquals(Protocolo.SEQ_NINGUNO, cola.ultimoSeq());
        cola.nuevaGeneracion(CONFIG);
        int gen = cola.generacion();
        for (long seq = 5; seq <= 7; seq++) {
            ColaBloques.Bloque b = cola.tomarLibre();
            assertNotNull(b);
            assertEquals(1032, b.datos.length);
            cola.encolar(b, seq);
        }
        assertEquals(3, cola.enCola());
        assertEquals(7, cola.ultimoSeq());
        for (long seq = 5; seq <= 7; seq++) {
            ColaBloques.Bloque b = cola.sacar(gen);
            assertEquals(seq, b.seq);
            cola.devolver(b);
        }
        assertNull(cola.sacar(gen));
        cola.recibido(8); // AUDIO descartado: cuenta igual para STATE
        assertEquals(8, cola.ultimoSeq());
        assertEquals(0, cola.enCola());
    }

    @Test
    public void poolAgotadoCuentaDesbordes() {
        ColaBloques cola = new ColaBloques(4, 520);
        for (int i = 0; i < 4; i++) {
            assertNotNull(cola.tomarLibre());
        }
        assertNull(cola.tomarLibre());
        assertNull(cola.tomarLibre());
        assertEquals(2, cola.desbordes());
    }

    @Test
    public void nuevaGeneracionDevuelveLoEncoladoAlPool() {
        ColaBloques cola = new ColaBloques(4, 520);
        cola.nuevaGeneracion(CONFIG);
        for (int i = 0; i < 4; i++) {
            cola.encolar(cola.tomarLibre(), i);
        }
        assertNull(cola.tomarLibre());
        int antes = cola.generacion();
        cola.nuevaGeneracion(null);
        assertEquals(antes + 1, cola.generacion());
        assertNull(cola.config());
        assertEquals(0, cola.enCola());
        for (int i = 0; i < 4; i++) {
            assertNotNull("los bloques volvieron al pool", cola.tomarLibre());
        }
        assertEquals(3, cola.ultimoSeq()); // VACIAR no borra el último seq recibido
    }

    @Test
    public void conGeneracionAtrasadaNoEntregaNiDescarta() {
        ColaBloques cola = new ColaBloques(4, 520);
        cola.nuevaGeneracion(CONFIG);
        int vieja = cola.generacion();
        cola.encolar(cola.tomarLibre(), 0);
        cola.nuevaGeneracion(CONFIG); // VACIAR
        ColaBloques.Bloque nuevo = cola.tomarLibre();
        cola.encolar(nuevo, 1);
        assertNull("el hilo de audio debe aplicar primero la generación nueva", cola.sacar(vieja));
        assertEquals("el bloque nuevo no se pierde", 1, cola.enCola());
        assertSame(nuevo, cola.sacar(cola.generacion()));
    }

    @Test
    public void actualizarConfigNoVacia() {
        ColaBloques cola = new ColaBloques(4, 520);
        cola.nuevaGeneracion(CONFIG);
        int gen = cola.generacion();
        cola.encolar(cola.tomarLibre(), 0);
        Config otroObjetivo = Config.imaAdpcm(48000, 2, 500);
        cola.actualizarConfig(otroObjetivo);
        assertEquals(gen, cola.generacion());
        assertSame(otroObjetivo, cola.config());
        assertEquals(1, cola.enCola());
    }

    /** Operaciones al azar: ningún bloque se pierde ni se duplica, y el orden se mantiene. */
    @Test
    public void conservaLosBloquesConOperacionesAlAzar() {
        int total = 16;
        ColaBloques cola = new ColaBloques(total, 520);
        cola.nuevaGeneracion(CONFIG);
        Random azar = new Random(7);
        ArrayDeque<ColaBloques.Bloque> esperados = new ArrayDeque<ColaBloques.Bloque>();
        List<ColaBloques.Bloque> fuera = new ArrayList<ColaBloques.Bloque>();
        long seq = 0;
        for (int i = 0; i < 20000; i++) {
            int op = azar.nextInt(10);
            if (op < 4) {
                ColaBloques.Bloque b = cola.tomarLibre();
                if (b != null) {
                    cola.encolar(b, seq++);
                    esperados.addLast(b);
                }
            } else if (op < 7) {
                ColaBloques.Bloque b = cola.sacar(cola.generacion());
                assertSame(esperados.pollFirst(), b);
                if (b != null) {
                    fuera.add(b);
                }
            } else if (op < 9) {
                if (!fuera.isEmpty()) {
                    cola.devolver(fuera.remove(azar.nextInt(fuera.size())));
                }
            } else {
                cola.nuevaGeneracion(CONFIG);
                esperados.clear();
            }
            assertEquals(esperados.size(), cola.enCola());
        }
        for (ColaBloques.Bloque b : fuera) {
            cola.devolver(b);
        }
        cola.nuevaGeneracion(null);
        int libres = 0;
        while (cola.tomarLibre() != null) {
            libres++;
        }
        assertEquals(total, libres);
    }

    /** Un aviso entre leer eventos() y esperar no se pierde: la espera vuelve enseguida. */
    @Test(timeout = 5000)
    public void esperarTrasNoPierdeAvisos() {
        ColaBloques cola = new ColaBloques(4, 520);
        cola.nuevaGeneracion(CONFIG);
        long visto = cola.eventos();
        cola.encolar(cola.tomarLibre(), 0); // llega antes de que el hilo de audio espere
        long t0 = System.nanoTime();
        cola.esperarTras(visto, 3000);
        assertTrue("no debió esperar", System.nanoTime() - t0 < 1000000000L);

        visto = cola.eventos();
        cola.despertar();
        t0 = System.nanoTime();
        cola.esperarTras(visto, 3000);
        assertTrue(System.nanoTime() - t0 < 1000000000L);

        visto = cola.eventos();
        t0 = System.nanoTime();
        cola.esperarTras(visto, 100); // sin avisos: espera el plazo
        assertTrue(System.nanoTime() - t0 >= 90000000L);
    }
}
