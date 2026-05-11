package tasf.strategy.alns;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.Solucion;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorRutasStrategy;

import java.util.HashMap;
import java.util.Map;

/**
 * ALNS_RutasPlanner: versión refactorizada que implementa PlanificadorRutasStrategy.
 * Retorna la solución paquete -> ruta construida por ALNS_Strategy.
 * 
 * Puede ser usado directamente como:
 * - PlanificadorRutasStrategy: planificación de rutas seleccionadas
 * - O encapsulado en TwoPhaseOrchestrator para flujo completo
 */
public class ALNS_RutasPlanner implements PlanificadorRutasStrategy {
    private final ALNS_Strategy delegate;

    public ALNS_RutasPlanner() {
        this(System.nanoTime());
    }

    public ALNS_RutasPlanner(long semilla) {
        this.delegate = new ALNS_Strategy(semilla);
    }

    /**
     * Planifica rutas usando ALNS y devuelve la mejor ruta para cada paquete.
     *
     * @param datos Dataset
     * @param config Configuración
     * @return Map de paquete -> ruta seleccionada
     */
    @Override
    public Map<String, Ruta> planificarRutas(Dataset datos, Config_Simulacion config) {
        Solucion solucion = delegate.planificar(datos, config);
        return new HashMap<>(solucion.getRutasAsignadas());
    }
}
