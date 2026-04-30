package tasf.experiments;

import java.util.Objects;

/**
 * Registro minimo para analisis estadistico de corridas.
 *
 * Campos requeridos:
 * - algoritmo
 * - nivel
 * - corrida
 * - porcentajeExito
 */
public final class ExperimentStatRecord {
    private final String algoritmo;
    private final int nivel;
    private final int corrida;
    private final double porcentajeExito;

    public ExperimentStatRecord(String algoritmo, int nivel, int corrida, double porcentajeExito) {
        this.algoritmo = Objects.requireNonNull(algoritmo, "algoritmo no puede ser null");
        this.nivel = nivel;
        this.corrida = corrida;
        this.porcentajeExito = porcentajeExito;
    }

    public String getAlgoritmo() {
        return algoritmo;
    }

    public int getNivel() {
        return nivel;
    }

    public int getCorrida() {
        return corrida;
    }

    public double getPorcentajeExito() {
        return porcentajeExito;
    }
}
