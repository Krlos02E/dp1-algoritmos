package tasf.model;

/**
 * Resultado de procesamiento de un envio.
 */
public class ResultadoEnvio {
    private final boolean asignado;
    private final boolean llegoATiempo;

    public ResultadoEnvio(boolean asignado, boolean llegoATiempo) {
        this.asignado = asignado;
        this.llegoATiempo = llegoATiempo;
    }

    public boolean isAsignado() {
        return asignado;
    }

    public boolean isLlegoATiempo() {
        return llegoATiempo;
    }
}
