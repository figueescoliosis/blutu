package cl.parlantey.pc;

import static cl.parlantey.pc.CapturaWindows.AUDCLNT_SHAREMODE_SHARED;
import static cl.parlantey.pc.CapturaWindows.CLIENT_GET_BUFFER_SIZE;
import static cl.parlantey.pc.CapturaWindows.CLIENT_GET_CURRENT_PADDING;
import static cl.parlantey.pc.CapturaWindows.CLIENT_GET_SERVICE;
import static cl.parlantey.pc.CapturaWindows.CLIENT_INITIALIZE;
import static cl.parlantey.pc.CapturaWindows.CLIENT_START;
import static cl.parlantey.pc.CapturaWindows.CLIENT_STOP;
import static cl.parlantey.pc.CapturaWindows.CLSCTX_ALL;
import static cl.parlantey.pc.CapturaWindows.CLSID_MMDeviceEnumerator;
import static cl.parlantey.pc.CapturaWindows.DEVICE_ACTIVATE;
import static cl.parlantey.pc.CapturaWindows.E_RENDER;
import static cl.parlantey.pc.CapturaWindows.IID_IAudioClient;
import static cl.parlantey.pc.CapturaWindows.IID_IAudioRenderClient;
import static cl.parlantey.pc.CapturaWindows.IID_IMMDeviceEnumerator;
import static cl.parlantey.pc.CapturaWindows.RENDER_GET_BUFFER;
import static cl.parlantey.pc.CapturaWindows.RENDER_RELEASE_BUFFER;
import static cl.parlantey.pc.CapturaWindows.llamar;
import static cl.parlantey.pc.CapturaWindows.soltar;
import static cl.parlantey.pc.CapturaWindows.verificar;

import cl.parlantey.core.Salida;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.util.Locale;

/**
 * El micrófono del Galaxy Y hacia Windows: una {@link Salida} WASAPI (modo compartido) sobre la
 * entrada de VB-Audio Virtual Cable («CABLE Input»), así las apps lo ven como el micrófono
 * «CABLE Output». No va a la salida predeterminada: esa se captura en loopback y volvería al Galaxy Y.
 *
 * <p>Recibe PCM16 a la tasa del micrófono y Windows convierte (AUTOCONVERTPCM). Buffer de 30 ms. Solo
 * la usa el hilo de audio de {@link MicrofonoPc}, que inicializa COM.
 */
final class SalidaWasapi implements Salida {

    /** Parte del nombre del dispositivo de VB-Cable («CABLE Input (VB-Audio Virtual Cable)»). */
    static final String NOMBRE_CABLE = "CABLE Input";
    static final String URL_CABLE = "https://vb-audio.com/Cable/";

    private static final int ENUM_AUDIO_ENDPOINTS = 3;
    private static final int DEVICE_STATE_ACTIVE = 1;
    private static final int COLLECTION_GET_COUNT = 3;
    private static final int COLLECTION_ITEM = 4;
    private static final int DEVICE_OPEN_PROPERTY_STORE = 4;
    private static final int STGM_READ = 0;
    private static final int PROPSTORE_GET_VALUE = 5;
    private static final int CLIENT_RESET = 12;
    private static final int AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM = 0x80000000;
    private static final int AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x08000000;
    private static final long BUFFER_100NS = 300000L; // 30 ms
    private static final short VT_LPWSTR = 31;
    /** PKEY_Device_FriendlyName, en el orden de memoria (GUID.toByteArray da el del texto). */
    private static final byte[] FMTID_NOMBRE = enMemoria(new Guid.GUID("{A45C254E-DF1C-4EFD-8020-67D146A850E0}"));
    private static final int PID_NOMBRE = 14;

    private final String buscado;

    SalidaWasapi() {
        this(NOMBRE_CABLE);
    }

    /** Para probar con otra salida (el diagnóstico). */
    SalidaWasapi(String buscado) {
        this.buscado = buscado;
    }

    /** Último error al abrir (para la ventana), o null. */
    volatile String error;
    /** Nombre del dispositivo abierto, o null. */
    volatile String dispositivo;

    private Pointer enumerador;
    private Pointer cable;
    private Pointer cliente;
    private Pointer render;
    private int canales;
    private int capacidad;
    private int escritos;
    private final IntByReference relleno = new IntByReference();
    private final PointerByReference datos = new PointerByReference();

    @Override
    public boolean abrir(int tasa, int canales) {
        cerrar();
        try {
            PointerByReference p = new PointerByReference();
            verificar(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, p),
                    "no se pudo abrir el audio de Windows");
            enumerador = p.getValue();
            cable = buscarCable();
            verificar(llamar(cable, DEVICE_ACTIVATE, IID_IAudioClient, CLSCTX_ALL, null, p), "IAudioClient");
            cliente = p.getValue();
            Memory formato = new Memory(18); // WAVEFORMATEX, PCM16
            formato.setShort(0, (short) 1);
            formato.setShort(2, (short) canales);
            formato.setInt(4, tasa);
            formato.setInt(8, tasa * canales * 2);
            formato.setShort(12, (short) (canales * 2));
            formato.setShort(14, (short) 16);
            formato.setShort(16, (short) 0);
            verificar(llamar(cliente, CLIENT_INITIALIZE, AUDCLNT_SHAREMODE_SHARED,
                    AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY, BUFFER_100NS, 0L,
                    formato, null), "Windows no aceptó el formato del micrófono");
            IntByReference frames = new IntByReference();
            verificar(llamar(cliente, CLIENT_GET_BUFFER_SIZE, frames), "GetBufferSize");
            capacidad = frames.getValue();
            verificar(llamar(cliente, CLIENT_GET_SERVICE, IID_IAudioRenderClient, p), "IAudioRenderClient");
            render = p.getValue();
            this.canales = canales;
            escritos = 0;
            error = null;
            return true;
        } catch (IOException | RuntimeException e) {
            error = e.getMessage();
            cerrar();
            return false;
        }
    }

    /** El primer dispositivo de salida activo cuyo nombre contiene {@link #buscado}. */
    private Pointer buscarCable() throws IOException {
        PointerByReference p = new PointerByReference();
        verificar(llamar(enumerador, ENUM_AUDIO_ENDPOINTS, E_RENDER, DEVICE_STATE_ACTIVE, p), "EnumAudioEndpoints");
        Pointer lista = p.getValue();
        try {
            IntByReference n = new IntByReference();
            verificar(llamar(lista, COLLECTION_GET_COUNT, n), "GetCount");
            for (int i = 0; i < n.getValue(); i++) {
                if (llamar(lista, COLLECTION_ITEM, i, p) < 0) {
                    continue;
                }
                Pointer d = p.getValue();
                String nombre = nombreDe(d);
                if (nombre != null && nombre.toLowerCase(Locale.ROOT).contains(buscado.toLowerCase(Locale.ROOT))) {
                    dispositivo = nombre;
                    return d;
                }
                soltar(d);
            }
        } finally {
            soltar(lista);
        }
        dispositivo = null;
        throw new IOException("no está «" + buscado + "»: instala VB-Audio Virtual Cable (" + URL_CABLE + ")");
    }

    /** Nombre visible de un IMMDevice (PKEY_Device_FriendlyName), o null. */
    private static String nombreDe(Pointer d) {
        PointerByReference p = new PointerByReference();
        if (llamar(d, DEVICE_OPEN_PROPERTY_STORE, STGM_READ, p) < 0) {
            return null;
        }
        Pointer propiedades = p.getValue();
        Memory clave = new Memory(20); // PROPERTYKEY: GUID + pid
        clave.write(0, FMTID_NOMBRE, 0, 16);
        clave.setInt(16, PID_NOMBRE);
        Memory valor = new Memory(24); // PROPVARIANT (24 bytes en x64)
        valor.clear();
        try {
            if (llamar(propiedades, PROPSTORE_GET_VALUE, clave, valor) < 0) {
                return null;
            }
            String nombre = valor.getShort(0) == VT_LPWSTR ? valor.getPointer(8).getWideString(0) : null;
            Function.getFunction("ole32", "PropVariantClear").invokeInt(new Object[] {valor});
            return nombre;
        } finally {
            soltar(propiedades);
        }
    }

    private static byte[] enMemoria(Guid.GUID g) {
        g.write();
        return g.getPointer().getByteArray(0, 16);
    }

    @Override
    public int capacidadFrames() {
        return capacidad;
    }

    @Override
    public int escribir(short[] pcm, int desde, int frames) {
        if (cliente == null || llamar(cliente, CLIENT_GET_CURRENT_PADDING, relleno) < 0) {
            return -1;
        }
        int n = Math.min(frames, capacidad - relleno.getValue());
        if (n <= 0) {
            return 0;
        }
        if (llamar(render, RENDER_GET_BUFFER, n, datos) < 0) {
            return -1;
        }
        datos.getValue().write(0, pcm, desde, n * canales);
        llamar(render, RENDER_RELEASE_BUFFER, n, 0);
        escritos += n;
        return n;
    }

    @Override
    public int framesReproducidos() {
        if (cliente == null || llamar(cliente, CLIENT_GET_CURRENT_PADDING, relleno) < 0) {
            return escritos; // salida perdida (p. ej. se desinstaló el cable): el Reproductor la reabre
        }
        return escritos - relleno.getValue();
    }

    @Override
    public void reproducir() {
        if (cliente != null) {
            llamar(cliente, CLIENT_START); // si ya estaba andando devuelve un error que no importa
        }
    }

    @Override
    public void pausar() {
        if (cliente != null) {
            llamar(cliente, CLIENT_STOP);
        }
    }

    @Override
    public void vaciar() {
        if (cliente != null) {
            llamar(cliente, CLIENT_STOP);
            llamar(cliente, CLIENT_RESET);
        }
        escritos = 0;
    }

    @Override
    public void detener() {
        vaciar();
    }

    @Override
    public void cerrar() {
        if (cliente != null) {
            llamar(cliente, CLIENT_STOP);
        }
        soltar(render);
        soltar(cliente);
        soltar(cable);
        soltar(enumerador);
        render = cliente = cable = enumerador = null;
        capacidad = 0;
        escritos = 0;
    }
}
