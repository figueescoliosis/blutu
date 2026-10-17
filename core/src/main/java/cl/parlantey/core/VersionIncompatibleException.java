package cl.parlantey.core;

/** El HELLO del receptor anuncia una versión de protocolo distinta de {@link Protocolo#VERSION}. */
public final class VersionIncompatibleException extends ProtocoloException {

    private static final long serialVersionUID = 1L;

    public final int versionRemota;

    public VersionIncompatibleException(int versionRemota) {
        super("versión de protocolo incompatible: remota " + versionRemota + ", local " + Protocolo.VERSION);
        this.versionRemota = versionRemota;
    }
}
