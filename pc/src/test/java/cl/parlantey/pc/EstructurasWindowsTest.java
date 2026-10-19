package cl.parlantey.pc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import cl.parlantey.core.Protocolo;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import org.junit.Test;

/** Lo que se puede comprobar de las estructuras de Windows sin Windows (JNA funciona en Linux). */
public class EstructurasWindowsTest {

    /** El UUID del servicio como GUID de Windows: Data1-3 little-endian, Data4 tal cual. */
    @Test
    public void guidDelServicio() {
        Memory m = new Memory(16);
        BluetoothWindows.escribirGuid(m, 0, Protocolo.UUID_SERVICIO); // c9cc3858-c5e1-46a3-9f9c-f1e94f108feb
        int[] esperado = {0x58, 0x38, 0xcc, 0xc9, 0xe1, 0xc5, 0xa3, 0x46, 0x9f, 0x9c, 0xf1, 0xe9, 0x4f, 0x10, 0x8f, 0xeb};
        for (int i = 0; i < 16; i++) {
            assertEquals("byte " + i, esperado[i], m.getByte(i) & 0xFF);
        }
    }

    /** Tamaños que exige la API de Bluetooth en x64. */
    @Test
    public void tamanosDeLasEstructurasBluetooth() {
        if (Native.POINTER_SIZE != 8 || Native.WCHAR_SIZE != 2) {
            return; // en Linux wchar_t es de 4 bytes: solo se comprueba donde aplica (Windows x64)
        }
        assertEquals(560, new BluetoothWindows.InfoDispositivo().size());
        assertEquals(40, new BluetoothWindows.ParamsBusqueda().size());
        assertEquals(4, new BluetoothWindows.ParamsRadio().size());
    }

    @Test
    public void formatoExtensibleFloat() throws Exception {
        Memory m = new Memory(40);
        m.clear();
        m.setShort(0, (short) 0xFFFE); // WAVE_FORMAT_EXTENSIBLE
        m.setShort(2, (short) 2);
        m.setInt(4, 48000);
        m.setShort(12, (short) 8);
        m.setShort(14, (short) 32);
        m.setShort(16, (short) 22);
        m.setInt(24, 3); // KSDATAFORMAT_SUBTYPE_IEEE_FLOAT
        CapturaWindows.Formato f = CapturaWindows.Formato.leer(m);
        assertEquals(48000, f.tasa);
        assertEquals(2, f.canales);
        assertEquals(32, f.bits);
        assertTrue(f.flotante);
        assertEquals("48 kHz float, 2 canales", f.texto());

        m.setInt(24, 1); // PCM entero
        m.setInt(4, 44100);
        m.setShort(14, (short) 24);
        f = CapturaWindows.Formato.leer(m);
        assertFalse(f.flotante);
        assertEquals(24, f.bits);
    }
}
