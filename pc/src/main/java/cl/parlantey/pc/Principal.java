package cl.parlantey.pc;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entrada: la ventana, o {@code --diagnostico} en la consola. */
public final class Principal {
    private Principal() {
    }

    public static void main(String[] args) {
        java.util.List<String> l = java.util.Arrays.asList(args);
        if (l.contains("--diagnostico")) {
            Diagnostico.ejecutar(System.out);
            if (l.contains("--pausa")) {
                // Abierto con doble clic: que la ventana no se cierre antes de poder copiar lo que dice.
                System.out.println();
                System.out.println("Presiona Enter para cerrar.");
                try {
                    System.in.read();
                } catch (java.io.IOException ignorada) {
                    // se cierra igual
                }
            }
            return;
        }
        Ajustes ajustes = new Ajustes();
        CapturaWindows.restaurarAlIniciar(ajustes); // si quedó silenciado de una ejecución anterior
        // Al salir de cualquier forma (cerrar la ventana, Ctrl+C), si el PC quedó silenciado se restaura.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> CapturaWindows.restaurarAlIniciar(ajustes), "ParlanteY-salida"));
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignorada) {
                // el aspecto por defecto sirve
            }
            new Ventana(ajustes, new BluetoothWindows(), () -> new CapturaWindows(ajustes)).mostrar();
        });
    }
}
