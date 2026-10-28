package cl.parlantey.receptor;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Cámara web por Wi-Fi: la cámara trasera en MJPEG por HTTP ({@link ServidorMjpeg}). En el PC,
 * OBS la toma como fuente y la publica como «OBS Virtual Camera».
 * La pantalla queda encendida: en 2.3 la vista previa necesita una superficie visible
 * (SurfaceTexture es de API 11) y al apagarse la pantalla se libera la cámara.
 */
@SuppressWarnings("deprecation") // android.hardware.Camera es la única API de cámara en 2.3
public class CamaraActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    static final int PUERTO = 8080;
    // Fluidez antes que nitidez: con calidad 100 cada cuadro de 320×240 pesaba 59 KB y el Wi-Fi
    // del Galaxy Y (≈ 100 KB/s medidos) daba 2 fps; con 50 pesa ≈ 7 KB.
    // ponytail: calidad fija; ajustarla según los KB/s medidos si el Wi-Fi varía mucho.
    private static final int CALIDAD_JPEG = 50;
    private static final long REFRESCO_MS = 1000;
    /** El primero es el que declara el Galaxy Y; los demás se piden a la fuerza (sensor 1600×1200). */
    private static final int[][] FORZADOS = {{320, 240}, {640, 480}, {800, 600}, {1280, 960}, {1600, 1200}};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ServidorMjpeg servidor = new ServidorMjpeg();
    private final long[] totales = new long[3];
    private final long[] totalesAntes = new long[3];

    private HandlerThread hiloJpeg;
    private WifiManager.WifiLock wifiLock;
    private Handler codificador;
    private SurfaceHolder superficie;
    private boolean superficieLista;
    private Camera camara;
    private int ancho;
    private int alto;
    /** Índice en {@link #FORZADOS}. */
    private int forzado;
    /** Tamaños que la cámara declara (para la pantalla). */
    private String declarados = "?";
    private String error = "";
    private String rangoFps = "?";
    /** Cuadros que entregó la cámara (hilo principal), se envíen o no. */
    private int cuadrosCamara;

    private TextView direccion;
    private TextView medicion;
    private Button botonTamano;

    /** Solo la usa el hilo del codificador; reutilizada entre cuadros. */
    private final Salida salida = new Salida();

    private static final class Salida extends ByteArrayOutputStream {
        Salida() {
            super(64 * 1024);
        }

        byte[] datos() {
            return buf;
        }
    }

    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            refrescar();
            ui.postDelayed(this, REFRESCO_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.camara);
        direccion = (TextView) findViewById(R.id.camara_direccion);
        medicion = (TextView) findViewById(R.id.camara_medicion);
        botonTamano = (Button) findViewById(R.id.camara_tamano);
        botonTamano.setOnClickListener(v -> cambiarTamano());

        SurfaceView vista = (SurfaceView) findViewById(R.id.camara_vista);
        superficie = vista.getHolder();
        superficie.addCallback(this);
        // Obligatorio antes de API 11 para la vista previa de la cámara.
        superficie.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        hiloJpeg = new HandlerThread("jpeg", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        hiloJpeg.start();
        codificador = new Handler(hiloJpeg.getLooper(), this::codificar);

        // Sin el ahorro de energía del Wi-Fi: menos cuadros atrasados y a tirones.
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, "parlantey-camara");
        wifiLock.setReferenceCounted(false);

        try {
            servidor.iniciar(PUERTO);
        } catch (IOException e) {
            error = getString(R.string.camara_error_puerto, PUERTO, e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            camara = Camera.open(); // la trasera; null si no hay
            if (camara == null) error = getString(R.string.camara_sin_camara);
        } catch (RuntimeException e) {
            error = getString(R.string.camara_ocupada);
        }
        if (camara != null && superficieLista) iniciarVista();
        wifiLock.acquire();
        ui.post(refresco);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresco);
        wifiLock.release();
        if (camara != null) {
            camara.setPreviewCallbackWithBuffer(null);
            camara.stopPreview();
            camara.release();
            camara = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        servidor.detener();
        hiloJpeg.quit();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        superficieLista = true;
        if (camara != null) iniciarVista();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int formato, int w, int h) {
        // orientación fija: nada que hacer
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        superficieLista = false;
        if (camara != null) camara.stopPreview();
    }

    /**
     * Recorre {@link #FORZADOS}. El Galaxy Y solo declara 320×240, pero algunos drivers aceptan
     * tamaños no declarados: se piden igual y se usa el que la cámara dice tener.
     */
    private void cambiarTamano() {
        if (camara == null || !superficieLista) return;
        forzado = (forzado + 1) % FORZADOS.length;
        iniciarVista();
    }

    private void iniciarVista() {
        try {
            camara.stopPreview();
            int[] pedido = FORZADOS[forzado];
            try {
                configurar(pedido[0], pedido[1]);
            } catch (RuntimeException e) {
                // El driver rechazó el tamaño: volver al declarado.
                error = getString(R.string.camara_rechazado, pedido[0], pedido[1]);
                configurar(FORZADOS[0][0], FORZADOS[0][1]);
            }
            forzarFps();
            Camera.Size real = camara.getParameters().getPreviewSize();
            ancho = real.width;
            alto = real.height;
            botonTamano.setText(getString(R.string.camara_tamano, ancho, alto));
            // Dos buffers: la cámara llena uno mientras el codificador comprime el otro.
            int bytes = ancho * alto * 3 / 2;
            camara.setPreviewCallbackWithBuffer(this);
            camara.addCallbackBuffer(new byte[bytes]);
            camara.addCallbackBuffer(new byte[bytes]);
            camara.setPreviewDisplay(superficie);
            camara.startPreview();
            // Todos los parámetros del driver, para ajustarlos desde el PC (/parametros).
            servidor.parametros = camara.getParameters().flatten().replace(';', '\n');
            if (ancho == pedido[0] && alto == pedido[1]) error = "";
        } catch (IOException | RuntimeException e) {
            error = getString(R.string.camara_error, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Con poca luz el Galaxy Y alarga la exposición hasta 7,5 fps (medido: 127 ms entre cuadros)
     * y no declara otro rango. Se prueban, cada uno por su cuenta, ajustes que el driver puede
     * aceptar aunque no los declare; si rechaza uno, queda lo anterior. /parametros muestra cuáles
     * quedaron.
     */
    private void forzarFps() {
        intentar("iso", "ISO400"); // ganancia en vez de exposición larga: más fps, más grano
        intentar("preview-fps-range", "30000,30000");
        intentar("preview-frame-rate", "30");
    }

    private void intentar(String clave, String valor) {
        try {
            Camera.Parameters p = camara.getParameters();
            p.set(clave, valor);
            camara.setParameters(p);
        } catch (RuntimeException rechazado) {
            // el driver no lo acepta: sigue lo de antes
        }
    }

    private void configurar(int w, int h) {
        Camera.Parameters p = camara.getParameters();
        StringBuilder lista = new StringBuilder();
        for (Camera.Size t : p.getSupportedPreviewSizes()) {
            lista.append(lista.length() == 0 ? "" : ", ").append(t.width).append('×').append(t.height);
        }
        declarados = lista.toString();
        p.setPreviewSize(w, h);
        p.setPreviewFormat(ImageFormat.NV21);
        // El driver de Samsung también mira el parámetro antiguo (venía en 15; admite 30).
        p.setPreviewFrameRate(30);
        // Por defecto el Galaxy Y entrega ≈ 8 fps (medido): pedir el rango más alto.
        // Mínimo alto = exposición corta: más fps, pero imagen más oscura con poca luz.
        rangoFps = "?";
        List<int[]> rangos = p.getSupportedPreviewFpsRange();
        if (rangos != null && !rangos.isEmpty()) {
            int[] mejor = rangos.get(0);
            for (int[] r : rangos) {
                int max = Camera.Parameters.PREVIEW_FPS_MAX_INDEX;
                int min = Camera.Parameters.PREVIEW_FPS_MIN_INDEX;
                if (r[max] > mejor[max] || (r[max] == mejor[max] && r[min] > mejor[min])) mejor = r;
            }
            p.setPreviewFpsRange(mejor[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                    mejor[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
            rangoFps = mejor[Camera.Parameters.PREVIEW_FPS_MIN_INDEX] / 1000 + "–"
                    + mejor[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] / 1000;
        }
        // El único rango del Galaxy Y es 7–30: con poca luz la exposición automática baja a
        // ≈ 8 fps (medido). Las escenas «deportes»/«acción» fuerzan exposiciones cortas.
        List<String> escenas = p.getSupportedSceneModes();
        String escena = null;
        if (escenas != null && escenas.contains(Camera.Parameters.SCENE_MODE_SPORTS)) {
            escena = Camera.Parameters.SCENE_MODE_SPORTS;
        } else if (escenas != null && escenas.contains(Camera.Parameters.SCENE_MODE_ACTION)) {
            escena = Camera.Parameters.SCENE_MODE_ACTION;
        }
        if (escena != null) p.setSceneMode(escena);
        // Red eléctrica de 50 Hz (Chile): sin esto, las luces parpadean en el video.
        List<String> antiparpadeo = p.getSupportedAntibanding();
        if (antiparpadeo != null && antiparpadeo.contains(Camera.Parameters.ANTIBANDING_50HZ)) {
            p.setAntibanding(Camera.Parameters.ANTIBANDING_50HZ);
        }
        rangoFps = rangoFps + ", " + (escena == null ? getString(R.string.camara_sin_escena) : escena);
        camara.setParameters(p);
    }

    /** Hilo principal: llega cada cuadro de la cámara. */
    @Override
    public void onPreviewFrame(byte[] datos, Camera c) {
        if (datos == null) return; // buffer de un tamaño anterior: la cámara lo descartó
        cuadrosCamara++;
        if (servidor.clientes() == 0) {
            c.addCallbackBuffer(datos); // nadie mira: no gastar CPU en JPEG
            return;
        }
        codificador.obtainMessage(0, ancho, alto, datos).sendToTarget();
    }

    /** Hilo «jpeg»: comprime, publica y devuelve el buffer a la cámara. */
    private boolean codificar(Message m) {
        final byte[] datos = (byte[]) m.obj;
        long t0 = System.nanoTime();
        salida.reset();
        new YuvImage(datos, ImageFormat.NV21, m.arg1, m.arg2, null)
                .compressToJpeg(new Rect(0, 0, m.arg1, m.arg2), CALIDAD_JPEG, salida);
        servidor.publicar(salida.datos(), salida.size(), System.nanoTime() - t0);
        ui.post(() -> devolver(datos));
        return true;
    }

    private void devolver(byte[] datos) {
        // Tras un cambio de tamaño, los buffers viejos se descartan.
        if (camara != null && datos.length == ancho * alto * 3 / 2) camara.addCallbackBuffer(datos);
    }

    private void refrescar() {
        String ip = ipWifi();
        direccion.setText(ip == null
                ? getString(R.string.camara_sin_wifi)
                : getString(R.string.camara_direccion, ip, PUERTO));

        servidor.totales(totales);
        long cuadros = totales[0] - totalesAntes[0];
        long bytes = totales[1] - totalesAntes[1];
        long ns = totales[2] - totalesAntes[2];
        System.arraycopy(totales, 0, totalesAntes, 0, 3);
        String texto = getString(R.string.camara_medicion, cuadros,
                cuadros == 0 ? "–" : Formato.decimal(ns / cuadros / 100000),
                cuadros == 0 ? 0 : bytes / cuadros / 1024, bytes / 1024, servidor.clientes())
                + "\n" + getString(R.string.camara_fps_camara, cuadrosCamara, rangoFps)
                + "\n" + getString(R.string.camara_declarados, declarados);
        cuadrosCamara = 0;
        medicion.setText(error.length() == 0 ? texto : texto + "\n" + error);
    }

    /** IP del Wi-Fi o null si no está conectado. */
    private String ipWifi() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int ip = wifi == null ? 0 : wifi.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        // getIpAddress() viene en orden little-endian.
        return (ip & 0xff) + "." + (ip >> 8 & 0xff) + "." + (ip >> 16 & 0xff) + "." + (ip >>> 24);
    }
}
