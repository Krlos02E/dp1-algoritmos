package tasf.strategy.flow;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.model.Ruta;

import java.util.Map;

/**
 * Implementacion del Strategy Asignador usando Min-Cost Flow.
 */
public class MinCostFlowAsignador implements Asignador {
    private final MinCostFlowAssigner delegate;

    public MinCostFlowAsignador() {
        this.delegate = new MinCostFlowAssigner();
    }

    @Override
    public Map<String, Ruta> asignar(
            Map<String, Ruta> rutasPlanificadas,
            Dataset datos,
            Config_Simulacion config
    ) {
        return delegate.asignarEnviosAVuelos(rutasPlanificadas, datos, config);
    }
}
