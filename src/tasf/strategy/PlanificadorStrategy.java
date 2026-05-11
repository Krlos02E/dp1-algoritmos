package tasf.strategy;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.Solucion;

/**
 * Interfaz para estrategias de planificación que devuelven una Solucion completa.
 * Usada por ALNS_Strategy y ACO_Strategy.
 */
public interface PlanificadorStrategy {
    Solucion planificar(Dataset datos, Config_Simulacion config);
}
