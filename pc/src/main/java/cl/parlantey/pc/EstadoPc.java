package cl.parlantey.pc;

import cl.parlantey.core.Preset;

/** Foto inmutable de lo que pasa, para la ventana. La publica el hilo de enlace. */
public final class EstadoPc {

    public enum Enlace { INACTIVO, CONECTANDO, CONECTADO, RECONECTANDO }

    /** Preset elegido: Auto o uno fijo. */
    public enum Opcion {
        AUTO("Auto", null), ALTA("Alta", Preset.ALTA), NORMAL("Normal", Preset.NORMAL), SEGURA("Segura", Preset.SEGURA);

        public final String etiqueta;
        public final Preset fijo;

        Opcion(String etiqueta, Preset fijo) {
            this.etiqueta = etiqueta;
            this.fijo = fijo;
        }

        /** Auto: el más alto con lo medido; sin medición, Normal; si nada alcanza, Segura. */
        public Preset resolver(double kBps) {
            if (fijo != null) {
                return fijo;
            }
            Preset p = Preset.elegirAuto(kBps);
            return p != null ? p : Preset.SEGURA;
        }
    }

    public Enlace enlace = Enlace.INACTIVO;
    public Dispositivo dispositivo;
    /** Nombre que el receptor dio en su HELLO. */
    public String receptor;
    public int intento;
    public String causa;
    public double rttMs = Double.NaN;
    public boolean midiendo;
    public double kBps = Double.NaN;

    public boolean enviando;
    public Opcion opcion = Opcion.AUTO;
    public Preset preset;
    public String formatoCaptura;
    public String formatoEnvio;
    public long bufferMs;
    public long cortes;
    public boolean sonando;
    /** Lo que espera para sonar: nivel estimado + lo pendiente en la captura. */
    public long latenciaMs;
    public long descartadosMs;
    /** Hace ≥ 3 s que llega solo silencio. */
    public boolean silencio;
    public boolean silenciado;

    /** El receptor manda su micrófono (app ParlanteY Mic). */
    public boolean mic;
    public boolean micSonando;
    /** Lo que espera para sonar en el PC (cola + salida). */
    public long micMs;
    public long micCortes;
    /** p. ej. falta VB-Cable. */
    public String micError;
    public String micDispositivo;

    public String error;
    public String aviso;

    EstadoPc copia() {
        EstadoPc e = new EstadoPc();
        e.enlace = enlace;
        e.dispositivo = dispositivo;
        e.receptor = receptor;
        e.intento = intento;
        e.causa = causa;
        e.rttMs = rttMs;
        e.midiendo = midiendo;
        e.kBps = kBps;
        e.enviando = enviando;
        e.opcion = opcion;
        e.preset = preset;
        e.formatoCaptura = formatoCaptura;
        e.formatoEnvio = formatoEnvio;
        e.bufferMs = bufferMs;
        e.cortes = cortes;
        e.sonando = sonando;
        e.latenciaMs = latenciaMs;
        e.descartadosMs = descartadosMs;
        e.silencio = silencio;
        e.silenciado = silenciado;
        e.mic = mic;
        e.micSonando = micSonando;
        e.micMs = micMs;
        e.micCortes = micCortes;
        e.micError = micError;
        e.micDispositivo = micDispositivo;
        e.error = error;
        e.aviso = aviso;
        return e;
    }
}
