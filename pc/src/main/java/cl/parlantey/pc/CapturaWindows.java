package cl.parlantey.pc;

import cl.parlantey.core.ColaCaptura;
import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lo que suena en el PC: WASAPI en modo loopback sobre la salida predeterminada de Windows, vía
 * COM con JNA (tablas de métodos a mano). Todo lo de COM ocurre en un solo hilo ("captura").
 *
 * <ul>
 * <li>El formato es el de la mezcla de Windows (casi siempre float de 32 bits a 48 kHz); el
 * {@link Conversor} lo pasa a PCM16 de 1 o 2 canales y el core baja la tasa si es mayor a 48 kHz.</li>
 * <li>Cuando nada suena, el loopback no entrega paquetes: un cliente de reproducción que solo escribe
 * silencio mantiene el reloj andando, así el receptor recibe silencio continuo y no cortes.</li>
 * <li>Silenciar = IAudioEndpointVolume::SetMute de esa salida. Si el programa se cierra a la fuerza
 * con el PC silenciado, {@link Ajustes#silencioPendiente} lo restaura la próxima vez.</li>
 * </ul>
 */
final class CapturaWindows implements FuenteSistema {

    private static final Guid.CLSID CLSID_MMDeviceEnumerator = new Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final Guid.IID IID_IMMDeviceEnumerator = new Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final Guid.IID IID_IAudioClient = new Guid.IID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}");
    private static final Guid.IID IID_IAudioCaptureClient = new Guid.IID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}");
    private static final Guid.IID IID_IAudioRenderClient = new Guid.IID("{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}");
    private static final Guid.IID IID_IAudioEndpointVolume = new Guid.IID("{5CDF2C82-841E-4546-9722-0CF74078229A}");
    private static final int CLSCTX_ALL = 0x17;
    private static final int COINIT_MULTITHREADED = 0;
    private static final int E_RENDER = 0;
    private static final int E_CONSOLE = 0;
    private static final int AUDCLNT_SHAREMODE_SHARED = 0;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
    private static final long BUFFER_100NS = 2000000L; // 200 ms
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

    // Índices en las tablas de métodos (después de los 3 de IUnknown).
    private static final int RELEASE = 2;
    private static final int ENUM_GET_DEFAULT_AUDIO_ENDPOINT = 4;
    private static final int ENUM_GET_DEVICE = 5;
    private static final int DEVICE_ACTIVATE = 3;
    private static final int DEVICE_GET_ID = 5;
    private static final int CLIENT_INITIALIZE = 3;
    private static final int CLIENT_GET_BUFFER_SIZE = 4;
    private static final int CLIENT_GET_CURRENT_PADDING = 6;
    private static final int CLIENT_GET_MIX_FORMAT = 8;
    private static final int CLIENT_START = 10;
    private static final int CLIENT_STOP = 11;
    private static final int CLIENT_GET_SERVICE = 14;
    private static final int CAPTURE_GET_BUFFER = 3;
    private static final int CAPTURE_RELEASE_BUFFER = 4;
    private static final int CAPTURE_GET_NEXT_PACKET_SIZE = 5;
    private static final int RENDER_GET_BUFFER = 3;
    private static final int RENDER_RELEASE_BUFFER = 4;
    private static final int VOLUME_SET_MUTE = 14;
    private static final int VOLUME_GET_MUTE = 15;

    private final Ajustes ajustes;

    private volatile boolean activa;
    private volatile Thread hilo;
    /** Un hilo de captura que no terminó a tiempo: no se abre otro mientras viva (comparten campos). */
    private volatile Thread anterior;
    private volatile ColaCaptura cola;
    private volatile String descripcion;
    private volatile String error;
    private volatile boolean silencioPedido;
    private volatile boolean silenciadoPorNosotros;

    CapturaWindows(Ajustes ajustes) {
        this.ajustes = ajustes;
    }

    @Override
    public synchronized ColaCaptura iniciar() throws IOException {
        detener();
        Thread viejo = anterior;
        if (viejo != null && viejo.isAlive()) {
            throw new IOException("la captura anterior todavía se está cerrando; intenta de nuevo en unos segundos");
        }
        error = null;
        CountDownLatch listo = new CountDownLatch(1);
        activa = true;
        Thread t = new Thread(() -> ejecutar(listo), "ParlanteY-captura");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        hilo = t;
        t.start();
        try {
            // Corto: mientras tanto el hilo de enlace no manda PING (el receptor corta a los 5 s).
            if (!listo.await(3, TimeUnit.SECONDS)) {
                detener();
                throw new IOException("Windows no respondió al abrir la captura de audio");
            }
        } catch (InterruptedException e) {
            detener();
            throw new IOException(e);
        }
        ColaCaptura c = cola;
        if (c == null) {
            String e = error;
            detener();
            throw new IOException(e != null ? e : "no se pudo abrir la captura de audio");
        }
        return c;
    }

    @Override
    public synchronized void detener() {
        activa = false;
        Thread t = hilo;
        hilo = null;
        if (t != null) {
            try {
                t.join(2000); // restaura el silencio y libera COM en su hilo
            } catch (InterruptedException ignorada) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                anterior = t;
            }
        }
        ColaCaptura c = cola;
        cola = null;
        if (c != null) {
            c.cerrar();
        }
        descripcion = null;
        silencioPedido = false;
    }

    @Override
    public void silenciar(boolean si) {
        silencioPedido = si;
    }

    @Override
    public boolean silenciado() {
        return silenciadoPorNosotros;
    }

    @Override
    public String descripcion() {
        return descripcion;
    }

    @Override
    public String error() {
        return error;
    }

    // --- Hilo de captura.

    private Pointer enumerador;
    private Pointer dispositivo;
    private Pointer cliente;
    private Pointer capturador;
    private Pointer clienteSilencio;
    private Pointer reproductorSilencio;
    private int framesSilencio;
    private Pointer volumen;
    private Pointer formato;
    private String idDispositivo;
    private Conversor conversor;
    private byte[] crudo = new byte[0];
    private short[] pcm = new short[0];

    private void ejecutar(CountDownLatch listo) {
        boolean com = false;
        try {
            Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED);
            com = true;
            try {
                abrir();
            } catch (IOException e) {
                error = e.getMessage();
                return;
            } finally {
                listo.countDown();
            }
            restaurarSilencioPendiente();
            int vueltas = 0;
            while (activa) {
                if (++vueltas % 100 == 0 && cambioLaSalida()) { // ~1 s
                    error = "cambió la salida de audio de Windows; vuelve a enviar para capturar la nueva";
                    break;
                }
                aplicarSilencio();
                try {
                    alimentarSilencio();
                    leerPaquetes();
                } catch (IOException e) {
                    // p. ej. AUDCLNT_E_DEVICE_INVALIDATED: se desconectaron los parlantes o cambió la salida.
                    error = "cambió la salida de audio de Windows (" + e.getMessage() + "); vuelve a enviar";
                    break;
                }
                dormir(10);
            }
        } catch (Throwable e) {
            error = "falla de la captura: " + e.getMessage();
        } finally {
            listo.countDown(); // también si COM no cargó: iniciar() no debe esperar en vano
            if (com) {
                if (silenciadoPorNosotros) {
                    ponerSilencio(false);
                }
                cerrarTodo();
                Ole32.INSTANCE.CoUninitialize();
            }
            ColaCaptura c = cola;
            if (c != null) {
                c.cerrar(); // el envío ve el fin y termina solo
            }
        }
    }

    private void abrir() throws IOException {
        PointerByReference p = new PointerByReference();
        verificar(Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, p),
                "no se pudo abrir el audio de Windows");
        enumerador = p.getValue();
        int hr = llamar(enumerador, ENUM_GET_DEFAULT_AUDIO_ENDPOINT, E_RENDER, E_CONSOLE, p);
        if (hr < 0) {
            throw new IOException("Windows no tiene una salida de audio activa (0x" + Integer.toHexString(hr) + ")");
        }
        dispositivo = p.getValue();
        idDispositivo = idDe(dispositivo);
        cliente = activar(IID_IAudioClient, "IAudioClient");
        verificar(llamar(cliente, CLIENT_GET_MIX_FORMAT, p), "no se pudo leer el formato de la salida");
        formato = p.getValue();
        Formato f = Formato.leer(formato);
        conversor = new Conversor(f.canales, f.bits, f.flotante, f.mascara);
        verificar(llamar(cliente, CLIENT_INITIALIZE, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, BUFFER_100NS,
                0L, formato, null), "Windows no permitió capturar la salida (loopback)");
        IntByReference frames = new IntByReference();
        verificar(llamar(cliente, CLIENT_GET_BUFFER_SIZE, frames), "GetBufferSize");
        crudo = new byte[frames.getValue() * conversor.bytesPorFrame()];
        pcm = new short[frames.getValue() * conversor.canalesSalida()];
        verificar(llamar(cliente, CLIENT_GET_SERVICE, IID_IAudioCaptureClient, p), "IAudioCaptureClient");
        capturador = p.getValue();
        abrirSilencio();
        try {
            volumen = activar(IID_IAudioEndpointVolume, "IAudioEndpointVolume");
        } catch (IOException e) {
            volumen = null; // sin silenciar, pero captura igual
        }
        ColaCaptura nueva = new ColaCaptura(f.tasa, conversor.canalesSalida(), Captura.MAX_PENDIENTE_MS);
        verificar(llamar(cliente, CLIENT_START), "no se pudo empezar la captura");
        descripcion = f.texto();
        cola = nueva; // recién ahora: iniciar() no devuelve una cola de una captura que no arrancó
    }

    /** Un cliente de reproducción que solo escribe silencio: con él, el loopback entrega paquetes siempre. */
    private void abrirSilencio() {
        try {
            clienteSilencio = activar(IID_IAudioClient, "IAudioClient");
            verificar(llamar(clienteSilencio, CLIENT_INITIALIZE, AUDCLNT_SHAREMODE_SHARED, 0, BUFFER_100NS, 0L, formato, null),
                    "Initialize");
            IntByReference frames = new IntByReference();
            verificar(llamar(clienteSilencio, CLIENT_GET_BUFFER_SIZE, frames), "GetBufferSize");
            framesSilencio = frames.getValue();
            PointerByReference p = new PointerByReference();
            verificar(llamar(clienteSilencio, CLIENT_GET_SERVICE, IID_IAudioRenderClient, p), "IAudioRenderClient");
            reproductorSilencio = p.getValue();
            alimentarSilencio();
            verificar(llamar(clienteSilencio, CLIENT_START), "Start");
        } catch (IOException e) {
            soltar(reproductorSilencio);
            soltar(clienteSilencio);
            reproductorSilencio = null;
            clienteSilencio = null; // sin él, los silencios largos llegan como cortes: se tolera
        }
    }

    private void alimentarSilencio() {
        if (reproductorSilencio == null) {
            return;
        }
        IntByReference relleno = new IntByReference();
        if (llamar(clienteSilencio, CLIENT_GET_CURRENT_PADDING, relleno) < 0) {
            return;
        }
        int libres = framesSilencio - relleno.getValue();
        if (libres <= 0) {
            return;
        }
        PointerByReference datos = new PointerByReference();
        if (llamar(reproductorSilencio, RENDER_GET_BUFFER, libres, datos) >= 0) {
            llamar(reproductorSilencio, RENDER_RELEASE_BUFFER, libres, AUDCLNT_BUFFERFLAGS_SILENT);
        }
    }

    private void leerPaquetes() throws IOException {
        IntByReference siguiente = new IntByReference();
        PointerByReference datos = new PointerByReference();
        IntByReference frames = new IntByReference();
        IntByReference flags = new IntByReference();
        while (activa) {
            verificar(llamar(capturador, CAPTURE_GET_NEXT_PACKET_SIZE, siguiente), "GetNextPacketSize");
            if (siguiente.getValue() == 0) {
                return;
            }
            verificar(llamar(capturador, CAPTURE_GET_BUFFER, datos, frames, flags, null, null), "GetBuffer");
            int n = frames.getValue();
            int bytes = n * conversor.bytesPorFrame();
            if (bytes > crudo.length) {
                crudo = new byte[bytes];
                pcm = new short[n * conversor.canalesSalida()];
            }
            if ((flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0) {
                java.util.Arrays.fill(pcm, 0, n * conversor.canalesSalida(), (short) 0);
            } else {
                datos.getValue().read(0, crudo, 0, bytes);
                conversor.convertir(crudo, n, pcm);
            }
            llamar(capturador, CAPTURE_RELEASE_BUFFER, n);
            ColaCaptura c = cola;
            if (c != null && n > 0) {
                c.escribir(pcm, 0, n);
            }
        }
    }

    // --- Silencio de la salida.

    private void aplicarSilencio() {
        boolean pedido = silencioPedido && volumen != null && idDispositivo != null; // sin ID no se podría restaurar
        if (pedido == silenciadoPorNosotros) {
            return;
        }
        if (pedido) {
            IntByReference ya = new IntByReference();
            if (llamar(volumen, VOLUME_GET_MUTE, ya) >= 0 && ya.getValue() != 0) {
                return; // ya estaba silenciado por el usuario: no es nuestro
            }
        }
        ponerSilencio(pedido);
    }

    private void ponerSilencio(boolean si) {
        if (volumen == null) {
            return;
        }
        if (si) {
            ajustes.silencioPendiente(idDispositivo); // antes: si el programa muere, se restaura la próxima vez
        }
        int hr = llamar(volumen, VOLUME_SET_MUTE, si ? 1 : 0, null);
        if (si) {
            silenciadoPorNosotros = hr >= 0;
            if (hr < 0) {
                ajustes.silencioPendiente(null);
            }
        } else {
            silenciadoPorNosotros = false;
            if (hr >= 0) {
                ajustes.silencioPendiente(null); // si falló (p. ej. se desconectó), queda pendiente para esa salida
            }
        }
    }

    /** Quita el silencio pendiente de una ejecución anterior, en la salida que se silenció. */
    private void restaurarSilencioPendiente() {
        String id = ajustes.silencioPendiente();
        if (id != null && enumerador != null && quitarSilencio(enumerador, id)) {
            ajustes.silencioPendiente(null);
        }
    }

    /** SetMute(false) en la salida {@code id}; true si Windows lo confirmó. */
    private static boolean quitarSilencio(Pointer enumerador, String id) {
        PointerByReference p = new PointerByReference();
        if (llamar(enumerador, ENUM_GET_DEVICE, new WString(id), p) < 0) {
            return false; // esa salida no está (desconectada): se intenta la próxima vez
        }
        Pointer dispositivo = p.getValue();
        try {
            if (llamar(dispositivo, DEVICE_ACTIVATE, IID_IAudioEndpointVolume, CLSCTX_ALL, null, p) < 0) {
                return false;
            }
            Pointer volumen = p.getValue();
            try {
                return llamar(volumen, VOLUME_SET_MUTE, 0, null) >= 0;
            } finally {
                soltar(volumen);
            }
        } finally {
            soltar(dispositivo);
        }
    }

    /** ID de un IMMDevice (o null). */
    private static String idDe(Pointer dispositivo) {
        PointerByReference p = new PointerByReference();
        if (llamar(dispositivo, DEVICE_GET_ID, p) < 0 || p.getValue() == null) {
            return null;
        }
        String id = p.getValue().getWideString(0);
        Ole32.INSTANCE.CoTaskMemFree(p.getValue());
        return id;
    }

    /** true si la salida predeterminada de Windows ya no es la que se captura. */
    private boolean cambioLaSalida() {
        if (idDispositivo == null) {
            return false;
        }
        PointerByReference p = new PointerByReference();
        if (llamar(enumerador, ENUM_GET_DEFAULT_AUDIO_ENDPOINT, E_RENDER, E_CONSOLE, p) < 0) {
            return false;
        }
        Pointer d = p.getValue();
        try {
            String id = idDe(d);
            return id != null && !id.equals(idDispositivo);
        } finally {
            soltar(d);
        }
    }

    /**
     * Quita el silencio que haya dejado una ejecución anterior (o esta, si se cierra sin haber podido
     * restaurarlo). Bloquea hasta 3 s. Se puede llamar desde cualquier hilo.
     */
    static void restaurarAlIniciar(Ajustes ajustes) {
        String id = ajustes.silencioPendiente();
        if (id == null) {
            return;
        }
        Thread t = new Thread(() -> {
            boolean com = false;
            Pointer enumerador = null;
            try {
                Ole32.INSTANCE.CoInitializeEx(null, COINIT_MULTITHREADED);
                com = true;
                PointerByReference p = new PointerByReference();
                if (Ole32.INSTANCE.CoCreateInstance(CLSID_MMDeviceEnumerator, null, CLSCTX_ALL, IID_IMMDeviceEnumerator, p)
                        .intValue() >= 0) {
                    enumerador = p.getValue();
                    if (quitarSilencio(enumerador, id)) {
                        ajustes.silencioPendiente(null);
                    }
                }
            } catch (Throwable ignorada) {
                // se intenta de nuevo la próxima vez
            } finally {
                soltar(enumerador);
                if (com) {
                    Ole32.INSTANCE.CoUninitialize();
                }
            }
        }, "ParlanteY-restaurar");
        t.start();
        try {
            t.join(3000);
        } catch (InterruptedException ignorada) {
            Thread.currentThread().interrupt();
        }
    }

    // --- COM.

    private Pointer activar(Guid.IID iid, String nombre) throws IOException {
        PointerByReference p = new PointerByReference();
        verificar(llamar(dispositivo, DEVICE_ACTIVATE, iid, CLSCTX_ALL, null, p), nombre);
        return p.getValue();
    }

    private void cerrarTodo() {
        if (cliente != null && capturador != null) {
            llamar(cliente, CLIENT_STOP);
        }
        if (clienteSilencio != null && reproductorSilencio != null) {
            llamar(clienteSilencio, CLIENT_STOP);
        }
        soltar(capturador);
        soltar(reproductorSilencio);
        soltar(clienteSilencio);
        soltar(cliente);
        soltar(volumen);
        soltar(dispositivo);
        soltar(enumerador);
        if (formato != null) {
            Ole32.INSTANCE.CoTaskMemFree(formato);
        }
        capturador = reproductorSilencio = clienteSilencio = cliente = volumen = dispositivo = enumerador = formato = null;
    }

    /** Llama al método {@code indice} de la tabla del objeto COM {@code obj}; devuelve el HRESULT. */
    static int llamar(Pointer obj, int indice, Object... args) {
        Pointer tabla = obj.getPointer(0);
        Function f = Function.getFunction(tabla.getPointer((long) indice * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] a = new Object[args.length + 1];
        a[0] = obj;
        System.arraycopy(args, 0, a, 1, args.length);
        return f.invokeInt(a);
    }

    private static void soltar(Pointer obj) {
        if (obj != null) {
            llamar(obj, RELEASE);
        }
    }

    private static void verificar(WinNT.HRESULT hr, String que) throws IOException {
        verificar(hr.intValue(), que);
    }

    private static void verificar(int hr, String que) throws IOException {
        if (hr < 0) {
            throw new IOException(que + " (0x" + Integer.toHexString(hr) + ")");
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** WAVEFORMATEX / WAVEFORMATEXTENSIBLE de la mezcla. */
    static final class Formato {
        final int tasa;
        final int canales;
        final int bits;
        final boolean flotante;
        /** dwChannelMask (0 si el formato no es extensible). */
        final int mascara;

        Formato(int tasa, int canales, int bits, boolean flotante, int mascara) {
            this.tasa = tasa;
            this.canales = canales;
            this.bits = bits;
            this.flotante = flotante;
            this.mascara = mascara;
        }

        static Formato leer(Pointer p) throws IOException {
            int etiqueta = p.getShort(0) & 0xFFFF;
            int canales = p.getShort(2) & 0xFFFF;
            int tasa = p.getInt(4);
            int bits = p.getShort(14) & 0xFFFF;
            boolean flotante = etiqueta == WAVE_FORMAT_IEEE_FLOAT;
            int mascara = 0;
            if (etiqueta == WAVE_FORMAT_EXTENSIBLE) {
                mascara = p.getInt(20);
                int sub = p.getInt(24); // Data1 del SubFormat: 1 PCM, 3 IEEE float
                flotante = sub == WAVE_FORMAT_IEEE_FLOAT;
            }
            if (tasa < 8000 || tasa > 192000) {
                throw new IOException("tasa de la salida no soportada: " + tasa + " Hz");
            }
            return new Formato(tasa, canales, bits, flotante, mascara);
        }

        String texto() {
            String t = tasa % 1000 == 0 ? (tasa / 1000) + "" : String.format("%.1f", tasa / 1000.0);
            return t + " kHz " + (flotante ? "float" : bits + " bits") + ", " + canales + " canales";
        }
    }

    /** Valores compartidos con la captura del emisor Android. */
    static final class Captura {
        static final long MAX_PENDIENTE_MS = 250;
    }
}
