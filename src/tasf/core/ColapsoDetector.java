package tasf.core;

import tasf.model.ResultadoEnvio;

import java.util.List;

/**
 * Detecta si el sistema logístico colapsa a partir de resultados de envios.
 */
public final class ColapsoDetector {

    private ColapsoDetector() {
    }

    /**
     * Hay colapso si al menos un envio no fue asignado
     * o si al menos un envio no llego a tiempo.
     *
     * @param resultados lista de resultados de envios
     * @return true si hay colapso, false en caso contrario
     */
    public static boolean hayColapso(List<ResultadoEnvio> resultados) {
        if (resultados == null || resultados.isEmpty()) {
            return false;
        }

        for (ResultadoEnvio resultado : resultados) {
            if (resultado == null) {
                continue;
            }
            if (!resultado.isAsignado() || !resultado.isLlegoATiempo()) {
                return true;
            }
        }

        return false;
    }
}
