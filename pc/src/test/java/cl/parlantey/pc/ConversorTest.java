package cl.parlantey.pc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class ConversorTest {

    private static ByteBuffer le(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    public void floatEstereo() {
        ByteBuffer b = le(4 * 2 * 3);
        b.putFloat(0f).putFloat(1f).putFloat(-1f).putFloat(0.5f).putFloat(2f).putFloat(Float.NaN);
        short[] s = new short[6];
        Conversor c = new Conversor(2, 32, true);
        c.convertir(b.array(), 3, s);
        assertEquals(2, c.canalesSalida());
        assertEquals(0, s[0]);
        assertEquals(32767, s[1]);
        assertEquals(-32767, s[2]);
        assertEquals(16384, s[3]);
        assertEquals("satura", 32767, s[4]);
        assertEquals("NaN es silencio", 0, s[5]);
    }

    @Test
    public void enteros16_24_32() {
        ByteBuffer b16 = le(4).putShort((short) -1234).putShort((short) 30000);
        short[] s = new short[2];
        new Conversor(2, 16, false).convertir(b16.array(), 1, s);
        assertEquals(-1234, s[0]);
        assertEquals(30000, s[1]);

        byte[] b24 = {0x00, 0x34, 0x12, 0x00, (byte) 0xCC, (byte) 0xED}; // 0x123400, 0xEDCC00
        new Conversor(2, 24, false).convertir(b24, 1, s);
        assertEquals(0x1234, s[0]);
        assertEquals((short) 0xEDCC, s[1]);

        ByteBuffer b32 = le(8).putInt(0x7FFF0000).putInt(0x80000000);
        new Conversor(2, 32, false).convertir(b32.array(), 1, s);
        assertEquals(32767, s[0]);
        assertEquals(-32768, s[1]);
    }

    /** 5.1 (FL FR FC LFE BL BR): el centro a los dos lados (−3 dB), traseros −6 dB, sin LFE. */
    @Test
    public void cincoPuntoUnoMezclaElCentro() {
        // Solo centro y LFE: la voz debe llegar a los dos lados; el LFE no.
        ByteBuffer b = le(4 * 6);
        b.putFloat(0f).putFloat(0f).putFloat(0.5f).putFloat(0.9f).putFloat(0f).putFloat(0f);
        short[] s = new short[2];
        Conversor c = new Conversor(6, 32, true, 0x3F); // KSAUDIO_SPEAKER_5POINT1
        c.convertir(b.array(), 1, s);
        assertEquals(2, c.canalesSalida());
        assertEquals(24, c.bytesPorFrame());
        int esperado = Math.round(0.5f * 32767 * 0.7071f);
        assertEquals(esperado, s[0], 1);
        assertEquals(esperado, s[1], 1);
        // Frontales y traseros a su lado; satura en vez de dar la vuelta.
        ByteBuffer b2 = le(4 * 6);
        b2.putFloat(0.4f).putFloat(-1f).putFloat(0f).putFloat(0f).putFloat(0.4f).putFloat(-1f);
        c.convertir(b2.array(), 1, s);
        assertEquals(Math.round(0.6f * 32767), s[0], 1);
        assertEquals(-32768, s[1]);
        Conversor mono = new Conversor(1, 16, false);
        assertEquals(1, mono.canalesSalida());
    }

    /** 7.1 con la máscara en 0: se usa el orden habitual. */
    @Test
    public void sieteUnoSinMascara() {
        ByteBuffer b = le(4 * 8);
        b.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0.2f).putFloat(0f);
        short[] s = new short[2];
        new Conversor(8, 32, true).convertir(b.array(), 1, s);
        assertEquals("lateral izquierdo a la izquierda", Math.round(0.1f * 32767), s[0], 1);
        assertEquals(0, s[1]);
    }

    @Test
    public void formatosNoSoportados() {
        try {
            new Conversor(2, 64, true);
            fail();
        } catch (IllegalArgumentException esperada) {
            // bien
        }
        try {
            new Conversor(9, 16, false);
            fail();
        } catch (IllegalArgumentException esperada) {
            // bien
        }
    }
}
