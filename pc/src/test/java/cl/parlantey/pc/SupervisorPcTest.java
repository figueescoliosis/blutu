package cl.parlantey.pc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import cl.parlantey.core.Protocolo;
import cl.parlantey.core.Reproductor;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import org.junit.After;
import org.junit.Test;

/** El supervisor del PC contra el receptor real del core, por tuberías en memoria y a tiempo real. */
public class SupervisorPcTest {

    private final ReceptorDePrueba receptor = new ReceptorDePrueba();
    private final FuenteDePrueba fuente = new FuenteDePrueba();
    private final Preferences nodo = Preferences.userRoot().node("cl/parlantey/pc/prueba-" + System.nanoTime());
    private final Ajustes ajustes = new Ajustes(nodo);
    private final Dispositivo galaxy = receptor.vinculados().get(0);
    private volatile EstadoPc ultimo = new EstadoPc();
    private SupervisorPc sup;

    @After
    public void limpiar() throws Exception {
        if (sup != null) {
            sup.detener();
            sup.esperarFin(5000);
        }
        receptor.terminar();
        nodo.removeNode();
    }

    private SupervisorPc nuevo(boolean silenciar) {
        sup = new SupervisorPc(receptor, fuente, ajustes, galaxy, EstadoPc.Opcion.AUTO, silenciar, e -> ultimo = e);
        sup.iniciar();
        return sup;
    }

    private static void esperarQue(String que, BooleanSupplier c, long ms) throws InterruptedException {
        long fin = System.currentTimeMillis() + ms;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > fin) {
                throw new AssertionError("no pasó en " + ms + " ms: " + que);
            }
            Thread.sleep(20);
        }
    }

    /** Primera vez con el receptor: mide el enlace solo y Auto queda en Alta (las tuberías son rápidas). */
    @Test
    public void primeraConexionMideElEnlace() throws Exception {
        nuevo(false);
        esperarQue("conectado", () -> ultimo.enlace == EstadoPc.Enlace.CONECTADO, 5000);
        assertEquals("GT-S5360L", ultimo.receptor);
        esperarQue("medido", () -> !Double.isNaN(ultimo.kBps) && !ultimo.midiendo, 20000);
        assertEquals(ultimo.kBps, ajustes.kBps(galaxy.direccion), 0.001);
        assertEquals("Alta", ultimo.preset.nombre);
    }

    @Test
    public void enviaEnVivoSinCortesYReconecta() throws Exception {
        ajustes.kBps(galaxy.direccion, 165);
        nuevo(false);
        esperarQue("conectado", () -> ultimo.enlace == EstadoPc.Enlace.CONECTADO, 5000);
        sup.enviar(true);
        esperarQue("suena", () -> receptor.reproductor.estado() == Reproductor.SONANDO, 5000);
        Thread.sleep(3000);
        assertTrue(ultimo.enviando);
        assertEquals(0, receptor.reproductor.cortes());
        assertTrue("en vivo", receptor.reproductor.config().enVivo);
        assertEquals("latencia Baja por defecto", 250, receptor.reproductor.config().bufferObjetivoMs);
        assertTrue("latencia " + ultimo.latenciaMs, ultimo.latenciaMs > 100 && ultimo.latenciaMs < 1200);
        assertFalse("hay señal", ultimo.silencio);
        assertEquals("48 kHz estéreo", ultimo.formatoEnvio);

        // Cambiar la latencia en vivo: CONFIG nuevo con el mismo formato, sigue sonando sin cortes.
        sup.latencia(150);
        esperarQue("CONFIG con 150 ms", () -> receptor.reproductor.config().bufferObjetivoMs == 150, 3000);
        Thread.sleep(2000);
        assertEquals(Reproductor.SONANDO, receptor.reproductor.estado());
        assertEquals("sin cortes al cambiar la latencia", 0, receptor.reproductor.cortes());
        assertEquals("250 ms, 150 ms", sup.resumenes().get(0).latencias);

        receptor.cortar();
        esperarQue("reconecta", () -> receptor.conexiones == 2 && ultimo.enlace == EstadoPc.Enlace.CONECTADO, 8000);
        esperarQue("vuelve a sonar", () -> receptor.reproductor.estado() == Reproductor.SONANDO, 5000);
        Thread.sleep(1500);
        assertEquals("sin cortes en la conexión nueva", 0, receptor.reproductor.cortes());
        assertEquals(1, sup.resumenes().get(0).reconexiones);

        sup.enviar(false);
        esperarQue("deja de enviar", () -> !ultimo.enviando, 3000);
        esperarQue("el receptor se detuvo", () -> receptor.reproductor.estado() == Reproductor.SIN_CONFIG, 3000);
        assertTrue(sup.resumenes().get(0).terminado);
    }

    @Test
    public void silenciaConSenalYQuitaElSilencioSiCallaLaCaptura() throws Exception {
        ajustes.kBps(galaxy.direccion, 165);
        nuevo(true);
        esperarQue("conectado", () -> ultimo.enlace == EstadoPc.Enlace.CONECTADO, 5000);
        sup.enviar(true);
        esperarQue("silenciado", () -> fuente.silenciada, 3000);
        Thread.sleep(1000);
        assertNull("con este PC silenciar no calla la captura", ultimo.aviso);
        sup.enviar(false);
        esperarQue("restaurado", () -> !fuente.silenciada, 3000);

        fuente.silencioCalla = true;
        sup.enviar(true);
        esperarQue("silenciado", () -> fuente.silenciada, 3000);
        esperarQue("quita el silencio", () -> !fuente.silenciada && ultimo.aviso != null, 6000);
        assertTrue(ultimo.aviso, ultimo.aviso.contains("calla la captura"));
        Thread.sleep(1000);
        assertFalse("no vuelve a silenciar", fuente.silenciada);
    }

    @Test
    public void otraVersionDelProtocolo() throws Exception {
        receptor.version = Protocolo.VERSION + 1;
        nuevo(false);
        esperarQue("termina", () -> !sup.vivo(), 5000);
        assertNotNull(ultimo.error);
        assertTrue(ultimo.error, ultimo.error.contains("protocolo"));
        assertEquals(EstadoPc.Enlace.INACTIVO, ultimo.enlace);
    }

    @Test
    public void detenerEnviaByeYNoReintenta() throws Exception {
        ajustes.kBps(galaxy.direccion, 165);
        nuevo(false);
        esperarQue("conectado", () -> ultimo.enlace == EstadoPc.Enlace.CONECTADO, 5000);
        sup.enviar(true);
        esperarQue("suena", () -> receptor.reproductor.estado() == Reproductor.SONANDO, 5000);
        sup.detener();
        sup.esperarFin(5000);
        assertFalse(sup.vivo());
        esperarQue("BYE", () -> receptor.byeRecibido == Protocolo.BYE_NORMAL, 3000);
        assertEquals(1, receptor.conexiones);
        assertFalse(fuente.silenciada);
    }

    @Test
    public void sinReceptorDaErrorClaro() throws Exception {
        receptor.fallarConexion = true;
        nuevo(false);
        esperarQue("termina", () -> !sup.vivo(), 5000);
        assertTrue(ultimo.error, ultimo.error.contains("No se pudo conectar"));
    }
}
