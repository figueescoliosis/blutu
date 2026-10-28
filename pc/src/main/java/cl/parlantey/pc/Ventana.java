package cl.parlantey.pc;

import cl.parlantey.core.Preset;
import cl.parlantey.core.Protocolo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

/** La ventana: elegir el Galaxy Y, conectar, enviar el audio del PC y ver cómo va. */
final class Ventana {

    private final Ajustes ajustes;
    private final Transporte.Conector conector;
    private final Supplier<FuenteSistema> fuentes;
    private SupervisorPc supervisor;
    private EstadoPc ultimo = new EstadoPc();

    private final JFrame marco = new JFrame("ParlanteY · PC");
    private final JComboBox<Dispositivo> dispositivos = new JComboBox<>();
    private final JButton actualizar = new JButton("Actualizar");
    private final JButton conectar = new JButton("Conectar");
    private final JLabel enlace = new JLabel("Elige el Galaxy Y y toca Conectar.");
    private final JButton enviar = new JButton("Enviar el audio del PC");
    private final JRadioButton[] opciones = new JRadioButton[EstadoPc.Opcion.values().length];
    private final JLabel auto = new JLabel(" ");
    private final JLabel pie = new JLabel(" ");
    private final JCheckBox silenciar = new JCheckBox("Silenciar el PC mientras envía (suena solo el Galaxy Y)");
    private final JLabel vivo = new JLabel(" ");
    private final JLabel formato = new JLabel(" ");
    private final JLabel aviso = new JLabel(" ");
    /** Micrófono del Galaxy Y (app ParlanteY Mic). */
    private final JLabel mic = new JLabel(" ");
    private final JButton probar = new JButton("Probar enlace (≈ 6 s)");
    private final JButton copiar = new JButton("Copiar para NOTAS");

    Ventana(Ajustes ajustes, Transporte.Conector conector, Supplier<FuenteSistema> fuentes) {
        this.ajustes = ajustes;
        this.conector = conector;
        this.fuentes = fuentes;
        armar();
    }

    void mostrar() {
        marco.setVisible(true);
        cargarVinculados();
    }

    private void armar() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel titulo = new JLabel("Manda lo que suena en este PC al Galaxy Y");
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 15f));
        agregar(p, titulo);
        agregar(p, new JLabel("<html>Una vez: en el Galaxy Y abre ParlanteY y toca <b>Hacer visible</b>; aquí, "
                + "Configuración de Windows → Bluetooth → Agregar dispositivo.</html>"));
        p.add(Box.createVerticalStrut(10));

        JPanel fila = new JPanel(new BorderLayout(6, 0));
        fila.add(dispositivos, BorderLayout.CENTER);
        fila.add(actualizar, BorderLayout.EAST);
        agregar(p, fila);
        agregar(p, conectar);
        enlace.setFont(enlace.getFont().deriveFont(Font.BOLD, 13f));
        agregar(p, enlace);
        p.add(Box.createVerticalStrut(10));

        enviar.setFont(enviar.getFont().deriveFont(Font.BOLD, 14f));
        agregar(p, enviar);
        JPanel presets = new JPanel();
        presets.setLayout(new BoxLayout(presets, BoxLayout.X_AXIS));
        presets.add(new JLabel("Preset: "));
        ButtonGroup g = new ButtonGroup();
        EstadoPc.Opcion guardada = opcionGuardada();
        for (EstadoPc.Opcion o : EstadoPc.Opcion.values()) {
            JRadioButton b = new JRadioButton(o.etiqueta, o == guardada);
            b.addActionListener(e -> elegir(o));
            g.add(b);
            presets.add(b);
            opciones[o.ordinal()] = b;
        }
        agregar(p, presets);
        agregar(p, auto);
        JPanel latencias = new JPanel();
        latencias.setLayout(new BoxLayout(latencias, BoxLayout.X_AXIS));
        latencias.add(new JLabel("Latencia: "));
        ButtonGroup gl = new ButtonGroup();
        long guardadaMs = ajustes.latenciaMs();
        for (int i = 0; i < LATENCIAS_MS.length; i++) {
            long ms = LATENCIAS_MS[i];
            JRadioButton b = new JRadioButton(LATENCIAS[i] + " " + ms + " ms", ms == guardadaMs);
            b.addActionListener(e -> elegirLatencia(ms));
            gl.add(b);
            latencias.add(b);
        }
        agregar(p, latencias);
        silenciar.setSelected(ajustes.silenciar());
        silenciar.addActionListener(e -> {
            ajustes.silenciar(silenciar.isSelected());
            if (supervisor != null) {
                supervisor.silenciar(silenciar.isSelected());
            }
        });
        agregar(p, silenciar);
        vivo.setFont(vivo.getFont().deriveFont(Font.BOLD, 14f));
        agregar(p, vivo);
        agregar(p, formato);
        agregar(p, mic);
        agregar(p, aviso);
        p.add(Box.createVerticalStrut(10));
        JPanel botones = new JPanel();
        botones.setLayout(new BoxLayout(botones, BoxLayout.X_AXIS));
        botones.add(probar);
        botones.add(Box.createHorizontalStrut(8));
        botones.add(copiar);
        agregar(p, botones);
        agregar(p, pie);
        mostrarPie();

        actualizar.addActionListener(e -> cargarVinculados());
        conectar.addActionListener(e -> alternarConexion());
        enviar.addActionListener(e -> {
            if (supervisor != null) {
                supervisor.enviar(!ultimo.enviando);
            }
        });
        probar.addActionListener(e -> {
            if (supervisor != null) {
                supervisor.probar();
            }
        });
        copiar.addActionListener(e -> copiarNotas());

        marco.setContentPane(p);
        marco.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        marco.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cerrar();
            }
        });
        marco.setMinimumSize(new Dimension(480, 520));
        marco.pack();
        marco.setLocationRelativeTo(null);
        mostrarEstado(ultimo);
    }

    private static void agregar(JPanel p, Component c) {
        if (c instanceof javax.swing.JComponent) {
            ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        p.add(c);
        p.add(Box.createVerticalStrut(6));
    }

    private EstadoPc.Opcion opcionGuardada() {
        try {
            return EstadoPc.Opcion.valueOf(ajustes.opcion());
        } catch (IllegalArgumentException e) {
            return EstadoPc.Opcion.AUTO;
        }
    }

    private void elegir(EstadoPc.Opcion o) {
        ajustes.opcion(o.name());
        if (supervisor != null) {
            supervisor.opcion(o);
        }
        mostrarEstado(ultimo);
    }

    private static final String[] LATENCIAS = {"Normal", "Baja", "Mínima"};
    private static final long[] LATENCIAS_MS = {500, 250, 150};

    private void elegirLatencia(long ms) {
        ajustes.latenciaMs(ms);
        if (supervisor != null) {
            supervisor.latencia(ms);
        }
        mostrarPie();
    }

    private void mostrarPie() {
        long ms = ajustes.latenciaMs();
        pie.setText("<html><small>Latencia ≈ " + ms + " ms + lo que tarda el enlace"
                + (ms < 500 ? "; si se corta, sube la latencia." : ".") + " Para video se nota el atraso.</small></html>");
    }

    private void cargarVinculados() {
        dispositivos.removeAllItems();
        try {
            List<Dispositivo> l = conector.vinculados();
            long ultimoUsado = ajustes.ultimoDispositivo();
            for (Dispositivo d : l) {
                dispositivos.addItem(d);
                if (d.direccion == ultimoUsado) {
                    dispositivos.setSelectedItem(d);
                }
            }
            if (l.isEmpty()) {
                aviso("No hay dispositivos vinculados. Vincula el Galaxy Y en Configuración → Bluetooth.", false);
            }
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            aviso(e.getMessage(), true);
        }
    }

    private void alternarConexion() {
        if (supervisor != null && supervisor.vivo()) {
            supervisor.detener();
            return;
        }
        Dispositivo d = (Dispositivo) dispositivos.getSelectedItem();
        if (d == null) {
            aviso("Elige el Galaxy Y en la lista (debe estar vinculado con este PC).", true);
            return;
        }
        ajustes.ultimoDispositivo(d.direccion);
        aviso(" ", false);
        SupervisorPc s = new SupervisorPc(conector, fuentes.get(), ajustes, d, opcionGuardada(), silenciar.isSelected(),
                e -> SwingUtilities.invokeLater(() -> mostrarEstado(e)));
        supervisor = s;
        s.iniciar();
    }

    private void mostrarEstado(EstadoPc e) {
        ultimo = e;
        boolean activo = supervisor != null && (supervisor.vivo() || e.enlace != EstadoPc.Enlace.INACTIVO);
        boolean conectado = e.enlace == EstadoPc.Enlace.CONECTADO;
        conectar.setText(activo ? "Desconectar" : "Conectar");
        dispositivos.setEnabled(!activo);
        actualizar.setEnabled(!activo);
        String nombre = e.dispositivo != null ? e.dispositivo.nombre : "el Galaxy Y";
        switch (e.enlace) {
            case CONECTANDO:
                enlace.setText("Conectando con " + nombre + "…");
                break;
            case RECONECTANDO:
                enlace.setText("Reconectando con " + nombre + " (intento " + e.intento + ")"
                        + (e.causa != null ? ": " + e.causa : "") + "…");
                break;
            case CONECTADO:
                enlace.setText("Conectado a " + (e.receptor != null ? e.receptor : nombre)
                        + (Double.isNaN(e.rttMs) ? "" : String.format(Locale.ROOT, " · RTT %.0f ms", e.rttMs))
                        + (e.midiendo ? " · midiendo el enlace…" : ""));
                break;
            default:
                enlace.setText(supervisor == null ? "Elige el Galaxy Y y toca Conectar." : "Desconectado.");
        }
        enviar.setEnabled(conectado && !e.midiendo);
        enviar.setText(e.enviando ? "Dejar de enviar" : "Enviar el audio del PC");
        probar.setEnabled(conectado && !e.enviando && !e.midiendo);
        Preset a = EstadoPc.Opcion.AUTO.resolver(e.kBps);
        auto.setText(Double.isNaN(e.kBps) ? "Auto: Normal (sin prueba de enlace todavía)"
                : String.format(Locale.ROOT, "Auto: %s (%.1f KB/s medidos)", a.nombre, e.kBps));
        if (e.enviando) {
            vivo.setText("Buffer " + e.bufferMs + " ms · " + e.cortes + " cortes · latencia ≈ " + e.latenciaMs + " ms");
            formato.setText("<html>Captura: " + (e.formatoCaptura != null ? e.formatoCaptura : "—") + " → "
                    + (e.formatoEnvio != null ? e.formatoEnvio : "—") + (e.preset != null ? " · " + e.preset.nombre : "")
                    + (e.silenciado ? " · PC silenciado" : "")
                    + (e.descartadosMs > 0 ? " · descartados " + e.descartadosMs + " ms" : "") + "</html>");
        } else {
            vivo.setText(" ");
            formato.setText(" ");
        }
        mostrarMicrofono(e);
        if (e.error != null) {
            aviso(e.error, true);
        } else if (e.aviso != null) {
            aviso(e.aviso, false);
        } else if (e.enviando && e.silencio) {
            aviso("Llega solo silencio: pon algo a sonar en el PC.", false);
        } else if (conectado) {
            aviso(" ", false);
        }
    }

    private void mostrarMicrofono(EstadoPc e) {
        if (!e.mic) {
            mic.setText(" ");
        } else if (e.micError != null && e.micDispositivo == null) {
            mic.setText("<html>Micrófono del Galaxy Y: <font color='#B00020'>" + e.micError + "</font></html>");
        } else {
            mic.setText("<html>Micrófono del Galaxy Y → <b>" + (e.micDispositivo != null ? e.micDispositivo : "…")
                    + "</b> · " + (e.micSonando ? "≈ " + e.micMs + " ms" : "esperando") + " · " + e.micCortes
                    + " cortes<br><small>En Discord/OBS/Zoom elige «CABLE Output» como micrófono.</small></html>");
        }
    }

    private void aviso(String texto, boolean esError) {
        aviso.setText("<html>" + (texto == null ? " " : texto) + "</html>");
        aviso.setForeground(esError ? new Color(0xB00020) : Color.DARK_GRAY);
    }

    private void copiarNotas() {
        StringBuilder sb = new StringBuilder();
        String v = Principal.class.getPackage().getImplementationVersion();
        sb.append("Emisor: PC ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" · ParlanteY PC ").append(v != null ? v : "dev").append(" · protocolo v").append(Protocolo.VERSION)
                .append("\n\n");
        sb.append("| Inicio | Duración | Preset | Objetivo | Formato | Cortes | Latencia ≈ mín/prom/máx (ms) | Buffer mín/prom/máx (ms) | "
                + "Descartado (ms) | Reconexiones |\n|---|---|---|---|---|---|---|---|---|---|\n");
        if (supervisor != null) {
            for (SupervisorPc.Resumen r : supervisor.resumenes()) {
                long d = (r.hastaMs - r.desdeMs) / 1000;
                String lat = r.muestras == 0 ? "—" : r.latenciaMin + "/" + r.latenciaSuma / r.muestras + "/" + r.latenciaMax;
                String buf = r.muestras == 0 ? "—" : r.bufferMin + "/" + r.bufferSuma / r.muestras + "/" + r.bufferMax;
                sb.append(String.format(Locale.ROOT, "| %s | %d:%02d:%02d%s | %s%s | %s | %s | %d | %s | %s | %d | %d |%n",
                        r.inicio, d / 3600, d / 60 % 60, d % 60, r.terminado ? "" : " (en curso)",
                        r.presets.isEmpty() ? "—" : r.presets, r.opcion.equals("Auto") ? " (Auto)" : "",
                        r.latencias.isEmpty() ? "—" : r.latencias, r.formato.isEmpty() ? "—" : r.formato, r.cortes, lat, buf, r.descartadosMs, r.reconexiones));
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
        JOptionPane.showMessageDialog(marco, "Copiado. Pégalo en el chat o en NOTAS.md.");
    }

    private void cerrar() {
        SupervisorPc s = supervisor;
        marco.setVisible(false);
        new Thread(() -> {
            if (s != null) {
                s.detener();
                try {
                    s.esperarFin(4000); // DETENER, BYE y restaurar el silencio del PC
                } catch (InterruptedException ignorada) {
                    Thread.currentThread().interrupt();
                }
            }
            System.exit(0);
        }, "ParlanteY-cierre").start();
    }
}
