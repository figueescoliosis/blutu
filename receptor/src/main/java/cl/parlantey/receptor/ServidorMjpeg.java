package cl.parlantey.receptor;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Servidor HTTP mínimo de la cámara web: {@code /video} es el flujo MJPEG
 * ({@code multipart/x-mixed-replace}) y {@code /} una página que lo muestra a pantalla completa
 * (para la fuente «Navegador» de OBS). Cada cliente tiene su hilo y siempre recibe el cuadro más
 * nuevo: si la red se atrasa, se salta cuadros en vez de acumular latencia.
 */
final class ServidorMjpeg {

    private static final String LIMITE = "cuadro";
    private static final String PAGINA = "<!doctype html><html><head><meta charset=\"utf-8\"><title>ParlanteY Cámara</title></head>"
            + "<body style=\"margin:0;background:#000\"><img src=\"/video\" style=\"display:block;width:100vw;height:100vh;object-fit:contain\">"
            + "</body></html>";

    private final Object lock = new Object();
    private ServerSocket servidor;
    private boolean detenido;
    /** Parámetros de la cámara, uno por línea ({@code /parametros}). */
    volatile String parametros = "";

    // Último cuadro (protegido por lock).
    private byte[] jpeg = new byte[0];
    private int largo;
    private long numero;
    private int clientes;

    // Totales para la medición en pantalla (protegidos por lock).
    private long totalBytes;
    private long totalCodificacionNs;

    void iniciar(int puerto) throws IOException {
        servidor = new ServerSocket(puerto);
        Thread t = new Thread(this::aceptar, "mjpeg-servidor");
        t.setDaemon(true);
        t.start();
    }

    void detener() {
        synchronized (lock) {
            detenido = true;
            lock.notifyAll();
        }
        try {
            if (servidor != null) servidor.close();
        } catch (IOException ignorada) {
            // ya cerrado
        }
    }

    int clientes() {
        synchronized (lock) {
            return clientes;
        }
    }

    /** {cuadros, bytes, ns de codificación} acumulados. */
    void totales(long[] salida) {
        synchronized (lock) {
            salida[0] = numero;
            salida[1] = totalBytes;
            salida[2] = totalCodificacionNs;
        }
    }

    /** Lo llama el hilo del codificador con cada JPEG nuevo. */
    void publicar(byte[] datos, int n, long codificacionNs) {
        synchronized (lock) {
            if (jpeg.length < n) jpeg = new byte[n * 2];
            System.arraycopy(datos, 0, jpeg, 0, n);
            largo = n;
            numero++;
            totalBytes += n;
            totalCodificacionNs += codificacionNs;
            lock.notifyAll();
        }
    }

    private void aceptar() {
        while (true) {
            final Socket s;
            try {
                s = servidor.accept();
            } catch (IOException e) {
                return; // detener() cerró el socket
            }
            Thread t = new Thread(() -> atender(s), "mjpeg-cliente");
            t.setDaemon(true);
            t.start();
        }
    }

    private void atender(Socket s) {
        try {
            s.setTcpNoDelay(true);
            BufferedReader entrada = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"));
            String pedido = entrada.readLine();
            for (String linea = pedido; linea != null && linea.length() > 0; ) {
                linea = entrada.readLine(); // cabeceras: no se usan
            }
            OutputStream salida = new BufferedOutputStream(s.getOutputStream(), 32 * 1024);
            if (pedido != null && pedido.startsWith("GET /video")) {
                enviarVideo(salida);
            } else if (pedido != null && pedido.startsWith("GET /parametros")) {
                byte[] texto = parametros.getBytes("UTF-8");
                escribir(salida, "HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                        + texto.length + "\r\nConnection: close\r\n\r\n");
                salida.write(texto);
            } else if (pedido != null && (pedido.startsWith("GET / ") || pedido.startsWith("GET /?"))) {
                byte[] html = PAGINA.getBytes("UTF-8");
                escribir(salida, "HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + html.length + "\r\nConnection: close\r\n\r\n");
                salida.write(html);
            } else {
                escribir(salida, "HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            }
            salida.flush();
        } catch (IOException | InterruptedException e) {
            // el cliente se fue
        } finally {
            try {
                s.close();
            } catch (IOException ignorada) {
                // ya cerrado
            }
        }
    }

    private void enviarVideo(OutputStream salida) throws IOException, InterruptedException {
        escribir(salida, "HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=" + LIMITE
                + "\r\nCache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n--" + LIMITE + "\r\n");
        salida.flush();
        synchronized (lock) {
            clientes++;
        }
        try {
            byte[] copia = new byte[64 * 1024];
            long visto = -1;
            while (true) {
                int n;
                synchronized (lock) {
                    while (numero == visto && !detenido) lock.wait();
                    if (detenido) return;
                    visto = numero;
                    n = largo;
                    if (copia.length < n) copia = new byte[jpeg.length];
                    System.arraycopy(jpeg, 0, copia, 0, n);
                }
                escribir(salida, "Content-Type: image/jpeg\r\nContent-Length: " + n + "\r\n\r\n");
                salida.write(copia, 0, n);
                // El límite va justo después del cuadro: Chrome (y OBS) muestran el cuadro al ver
                // el límite siguiente; si se mandara con el próximo cuadro, irían uno atrasados.
                escribir(salida, "\r\n--" + LIMITE + "\r\n");
                salida.flush();
            }
        } finally {
            synchronized (lock) {
                clientes--;
            }
        }
    }

    private static void escribir(OutputStream salida, String texto) throws IOException {
        salida.write(texto.getBytes("US-ASCII"));
    }
}
