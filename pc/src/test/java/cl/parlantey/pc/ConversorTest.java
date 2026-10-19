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

    /** 5.1 (6 canales): se toman frontal izquierdo y derecho. Mono queda mono. */
    @Test
    public void multicanalYMono() {
        ByteBuffer b = le(4 * 6 * 2);
        for (int f = 0; f < 2; f++) {
            for (int c = 0; c < 6; c++) {
                b.putFloat((c + 1) / 10f);
            }
        }
        short[] s = new short[4];
        Conversor c = new Conversor(6, 32, true);
        c.convertir(b.array(), 2, s);
        assertEquals(2, c.canalesSalida());
        assertEquals(24, c.bytesPorFrame());
        assertEquals(Math.round(0.1f * 32767), s[0]);
        assertEquals(Math.round(0.2f * 32767), s[1]);
        assertEquals(Math.round(0.1f * 32767), s[2]);
        Conversor mono = new Conversor(1, 16, false);
        assertEquals(1, mono.canalesSalida());
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
