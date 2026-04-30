package tasf.strategy.alns;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorRutasStrategy;

import java.util.List;
import java.util.Map;

/**
 * ALNS_RutasPlanner: versión refactorizada que implementa PlanificadorRutasStrategy.
 * Retorna rutas candidatas sin evaluación de asignación a vuelos.
 * 
 * Puede ser usado directamente como:
 * - PlanificadorRutasStrategy: solo planificación de rutas
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
     * Planifica rutas usando ALNS y devuelve rutas candidatas.
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
        
        // Retornar candidatos (el algoritmo ALNS elegirá dentro de estos)
        return candidatos;
    }

    /**
     * Getter del delegado para acceso a capacidades avanzadas.
     */
    public ALNS_Strategy getDelegate() {
        return delegate;
    }
}
