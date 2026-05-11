package tasf.strategy.aco;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.Solucion;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorRutasStrategy;

import java.util.HashMap;
import java.util.Map;

/**
 * ACO_RutasPlanner: versión refactorizada que implementa PlanificadorRutasStrategy.
 * Retorna la solución paquete -> ruta construida por ACO_Strategy.
 * 
 * Puede ser usado directamente como:
 * - PlanificadorRutasStrategy: planificación de rutas seleccionadas
 * - O encapsulado en TwoPhaseOrchestrator para flujo completo
 */
public class ACO_RutasPlanner implements PlanificadorRutasStrategy {
    private final ACO_Strategy delegate;

    public ACO_RutasPlanner() {
        this(System.nanoTime());
    }

    public ACO_RutasPlanner(long semilla) {
        this.delegate = new ACO_Strategy(semilla);
    }

    /**
     * Planifica rutas usando ACO y devuelve la mejor ruta para cada paquete.
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
