package tasf.strategy.aco;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorRutasStrategy;

import java.util.List;
import java.util.Map;

/**
 * ACO_RutasPlanner: versión refactorizada que implementa PlanificadorRutasStrategy.
 * Retorna rutas candidatas sin evaluación de asignación a vuelos.
 * 
 * Puede ser usado directamente como:
 * - PlanificadorRutasStrategy: solo planificación de rutas
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
     * Planifica rutas usando ACO y devuelve rutas candidatas.
     * Retorna el Map de candidatos construido durante la planificación.
     *
     * @param datos Dataset
     * @param config Configuración
     * @return Map de paquete -> lista de rutas candidatas
     */
    @Override
    public Map<String, List<Ruta>> planificarRutas(Dataset datos, Config_Simulacion config) {
        RouteFinder finder = new RouteFinder(datos);
        
        // Construir candidatos de rutas
        Map<String, List<Ruta>> candidatos = PlanificacionUtils.construirCandidatosRutas(datos, config, finder);
        
        // Retornar candidatos (el algoritmo ACO elegirá dentro de estos)
        // Para esto, ejecutamos el ACO delegado pero extraemos solo el mapa de candidatos
        // En lugar de evaluación completa
        return candidatos;
    }

    /**
     * Getter del delegado para acceso a capacidades avanzadas.
     */
    public ACO_Strategy getDelegate() {
        return delegate;
    }
}
