package cl.parlantey.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SeqTest {

    @Test
    public void siguienteDaLaVuelta() {
        assertEquals(1, Seq.siguiente(0));
        assertEquals(0, Seq.siguiente(0xFFFFFFFFL));
        assertEquals(0x80000000L, Seq.siguiente(0x7FFFFFFFL));
    }

    @Test
    public void distanciaConVuelta() {
        assertEquals(0, Seq.distancia(3, 3));
        assertEquals(2, Seq.distancia(5, 3));
        assertEquals("primer AUDIO sin STATE previo", 1, Seq.distancia(0, Protocolo.SEQ_NINGUNO));
        assertEquals(4, Seq.distancia(2, 0xFFFFFFFEL));
        assertEquals(-1, Seq.distancia(0xFFFFFFFFL, 0));
    }
}
