package tasf.strategy;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.Solucion;

public interface PlanificadorStrategy {
    Solucion planificar(Dataset datos, Config_Simulacion config);
}
