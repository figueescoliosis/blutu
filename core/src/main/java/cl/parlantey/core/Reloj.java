package cl.parlantey.core;

/** Fuente de tiempo en nanosegundos. Los tests de simulación usan un reloj virtual. */
public interface Reloj {

    Reloj SISTEMA = System::nanoTime;

    long nanos();
}
