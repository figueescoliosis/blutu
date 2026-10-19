package cl.parlantey.pc;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Entrada: la ventana, o {@code --diagnostico} en la consola. */
public final class Principal {
    private Principal() {
    }

    public static void main(String[] args) {
        for (String a : args) {
            if (a.equals("--diagnostico")) {
                Diagnostico.ejecutar(System.out);
                return;
            }
        }
        Ajustes ajustes = new Ajustes();
        CapturaWindows.restaurarAlIniciar(ajustes); // si quedó silenciado de una ejecución anterior
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
