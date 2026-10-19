package cl.parlantey.pc;

import cl.parlantey.core.Protocolo;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bluetooth del stack de Microsoft (Windows 10/11) vía JNA: dispositivos vinculados
 * (BluetoothFindFirstDevice) y un socket RFCOMM de Winsock ({@code AF_BTH}) conectado por el UUID
 * del servicio de ParlanteY: Windows busca el canal por SDP. Solo x64.
 */
final class BluetoothWindows implements Transporte.Conector {

    private static final int AF_BTH = 32;
    private static final int SOCK_STREAM = 1;
    private static final int BTHPROTO_RFCOMM = 3;
    private static final long INVALID_SOCKET = -1L;
    private static final int SOCKET_ERROR = -1;
    /** sizeof(SOCKADDR_BTH): la estructura está empaquetada (pshpack1). */
    private static final int LARGO_SOCKADDR_BTH = 30;

    /** BluetoothAPIs.dll (o bthprops.cpl en algunas instalaciones). */
    public interface Bth extends Library {
        Pointer BluetoothFindFirstRadio(ParamsRadio p, PointerByReference radio);

        boolean BluetoothFindRadioClose(Pointer h);

        Pointer BluetoothFindFirstDevice(ParamsBusqueda p, InfoDispositivo info);

        boolean BluetoothFindNextDevice(Pointer h, InfoDispositivo info);

        boolean BluetoothFindDeviceClose(Pointer h);
    }

    public interface Ws2 extends Library {
        int WSAStartup(short version, Pointer datos);

        long socket(int af, int tipo, int protocolo);

        int connect(long s, Pointer direccion, int largo);

        int send(long s, Pointer buf, int largo, int flags);

        int recv(long s, Pointer buf, int largo, int flags);

        int shutdown(long s, int como);

        int closesocket(long s);

        int WSAGetLastError();
    }

    @Structure.FieldOrder({"dwSize"})
    public static class ParamsRadio extends Structure {
        public int dwSize;

        public ParamsRadio() {
            dwSize = size();
        }
    }

    @Structure.FieldOrder({"dwSize", "fReturnAuthenticated", "fReturnRemembered", "fReturnUnknown", "fReturnConnected",
        "fIssueInquiry", "cTimeoutMultiplier", "hRadio"})
    public static class ParamsBusqueda extends Structure {
        public int dwSize;
        public int fReturnAuthenticated = 1;
        public int fReturnRemembered = 1;
        public int fReturnUnknown;
        public int fReturnConnected = 1;
        public int fIssueInquiry;
        public byte cTimeoutMultiplier;
        public Pointer hRadio;

        public ParamsBusqueda() {
            dwSize = size();
        }
    }

    @Structure.FieldOrder({"dwSize", "address", "ulClassofDevice", "fConnected", "fRemembered", "fAuthenticated",
        "stLastSeen", "stLastUsed", "szName"})
    public static class InfoDispositivo extends Structure {
        public int dwSize;
        public long address;
        public int ulClassofDevice;
        public int fConnected;
        public int fRemembered;
        public int fAuthenticated;
        public short[] stLastSeen = new short[8];
        public short[] stLastUsed = new short[8];
        public char[] szName = new char[248];

        public InfoDispositivo() {
            dwSize = size();
        }
    }

    private static volatile Bth bth;
    private static volatile Ws2 ws2;

    private static Bth bth() throws IOException {
        Bth b = bth;
        if (b == null) {
            try {
                b = Native.load("BluetoothApis", Bth.class);
            } catch (UnsatisfiedLinkError e) {
                try {
                    b = Native.load("bthprops.cpl", Bth.class);
                } catch (UnsatisfiedLinkError e2) {
                    throw new IOException("este Windows no tiene las funciones de Bluetooth (" + e2.getMessage() + ")");
                }
            }
            bth = b;
        }
        return b;
    }

    private static synchronized Ws2 ws2() throws IOException {
        Ws2 w = ws2;
        if (w == null) {
            w = Native.load("Ws2_32", Ws2.class);
            int r = w.WSAStartup((short) 0x0202, new Memory(1024));
            if (r != 0) {
                throw new IOException("WSAStartup falló (" + r + ")");
            }
            ws2 = w;
        }
        return w;
    }

    /** true si hay al menos un adaptador Bluetooth encendido. */
    static boolean hayRadio() throws IOException {
        PointerByReference radio = new PointerByReference();
        Pointer h = bth().BluetoothFindFirstRadio(new ParamsRadio(), radio);
        if (h == null) {
            return false;
        }
        bth().BluetoothFindRadioClose(h);
        Kernel32.INSTANCE.CloseHandle(new WinNT.HANDLE(radio.getValue()));
        return true;
    }

    @Override
    public List<Dispositivo> vinculados() throws IOException {
        if (!hayRadio()) {
            throw new IOException("Este PC no tiene Bluetooth, o está apagado.");
        }
        List<Dispositivo> l = new ArrayList<>();
        InfoDispositivo info = new InfoDispositivo();
        Pointer h = bth().BluetoothFindFirstDevice(new ParamsBusqueda(), info);
        if (h == null) {
            return l; // ninguno (ERROR_NO_MORE_ITEMS)
        }
        try {
            do {
                if (info.fRemembered != 0 || info.fAuthenticated != 0) {
                    String nombre = Native.toString(info.szName);
                    Dispositivo d = new Dispositivo(nombre.isEmpty() ? "(sin nombre)" : nombre, info.address);
                    if (!l.contains(d)) {
                        l.add(d);
                    }
                }
                info = new InfoDispositivo();
            } while (bth().BluetoothFindNextDevice(h, info));
        } finally {
            bth().BluetoothFindDeviceClose(h);
        }
        return l;
    }

    private volatile Socket conectando;

    @Override
    public Transporte conectar(Dispositivo d) throws IOException {
        Ws2 w = ws2();
        long s = w.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
        if (s == INVALID_SOCKET) {
            throw new IOException("no se pudo crear el socket Bluetooth (" + w.WSAGetLastError() + ")");
        }
        Socket sock = new Socket(w, s);
        conectando = sock;
        try {
            Memory dir = new Memory(LARGO_SOCKADDR_BTH);
            dir.clear();
            dir.setShort(0, (short) AF_BTH);
            dir.setLong(2, d.direccion);
            escribirGuid(dir, 10, Protocolo.UUID_SERVICIO);
            dir.setInt(26, 0); // canal 0: Windows lo busca por SDP con el UUID
            if (w.connect(s, dir, LARGO_SOCKADDR_BTH) == SOCKET_ERROR) {
                int err = w.WSAGetLastError();
                sock.cerrar();
                throw new IOException(textoError(err));
            }
            return sock;
        } finally {
            conectando = null;
        }
    }

    @Override
    public void cancelar() {
        Socket s = conectando;
        if (s != null) {
            s.cerrar();
        }
    }

    /** GUID en memoria: Data1 u32, Data2 u16, Data3 u16 (little-endian) y Data4 8 bytes tal cual. */
    static void escribirGuid(Pointer p, long off, UUID u) {
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        p.setInt(off, (int) (msb >>> 32));
        p.setShort(off + 4, (short) (msb >>> 16));
        p.setShort(off + 6, (short) msb);
        for (int i = 0; i < 8; i++) {
            p.setByte(off + 8 + i, (byte) (lsb >>> (56 - 8 * i)));
        }
    }

    static String textoError(int err) {
        switch (err) {
            case 10060:
                return "el Galaxy Y no respondió (¿encendido, cerca y con ParlanteY Receptor abierto?)";
            case 10064:
            case 10065:
                return "el Galaxy Y no está al alcance o tiene el Bluetooth apagado";
            case 10061:
            case 10049:
                return "el Galaxy Y no tiene ParlanteY Receptor esperando (ábrelo)";
            case 10013:
                return "Windows negó la conexión: vincula el Galaxy Y en Configuración → Bluetooth";
            case 10050:
                return "el Bluetooth de este PC está apagado";
            case 10004:
            case 10038:
                return "conexión cancelada";
            default:
                return "error de Bluetooth " + err;
        }
    }

    /** Un socket RFCOMM conectado, como InputStream/OutputStream. */
    static final class Socket implements Transporte {
        private final Ws2 w;
        private final long s;
        private final AtomicBoolean cerrado = new AtomicBoolean();
        private final Memory bufEntrada = new Memory(65536);
        private final Memory bufSalida = new Memory(65536);

        Socket(Ws2 w, long s) {
            this.w = w;
            this.s = s;
        }

        private final InputStream entrada = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                synchronized (bufEntrada) {
                    if (cerrado.get()) {
                        throw new IOException("conexión cerrada");
                    }
                    int n = w.recv(s, bufEntrada, (int) Math.min(len, bufEntrada.size()), 0);
                    if (n == 0) {
                        return -1;
                    }
                    if (n < 0) {
                        throw new IOException(cerrado.get() ? "conexión cerrada" : textoError(w.WSAGetLastError()));
                    }
                    bufEntrada.read(0, b, off, n);
                    return n;
                }
            }
        };

        private final OutputStream salida = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                synchronized (bufSalida) {
                    while (len > 0) {
                        if (cerrado.get()) {
                            throw new IOException("conexión cerrada");
                        }
                        int trozo = (int) Math.min(len, bufSalida.size());
                        bufSalida.write(0, b, off, trozo);
                        int enviado = 0;
                        while (enviado < trozo) {
                            int n = w.send(s, bufSalida.share(enviado), trozo - enviado, 0);
                            if (n < 0) {
                                throw new IOException(cerrado.get() ? "conexión cerrada" : textoError(w.WSAGetLastError()));
                            }
                            enviado += n;
                        }
                        off += trozo;
                        len -= trozo;
                    }
                }
            }
        };

        @Override
        public InputStream entrada() {
            return entrada;
        }

        @Override
        public OutputStream salida() {
            return salida;
        }

        @Override
        public void cerrar() {
            if (cerrado.compareAndSet(false, true)) {
                w.shutdown(s, 2);
                w.closesocket(s); // desbloquea recv/send/connect en curso
            }
        }
    }
}
