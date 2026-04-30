package tasf.strategy;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.core.Solucion;
import tasf.model.Ruta;
import tasf.model.Vuelo;
import tasf.strategy.flow.Asignador;
import tasf.strategy.flow.MinCostFlowAsignador;

import java.util.List;
import java.util.Map;

/**
 * Orquestador del flujo de solución en dos fases:
 * 
 * Fase 1: Planificación de Rutas
 * - Usa un metaheurístico (ACO, ALNS, etc.)
 * - Devuelve rutas candidatas para cada paquete
 * 
 * Fase 2: Asignación de Envíos a Vuelos
 * - Usa Min-Cost Flow
 * - Asigna envíos a vuelos específicos minimizando costo total
 * - Produce la solución final evaluada
 */
public class TwoPhaseOrchestrator {
    private final PlanificadorRutasStrategy planificador;
    private final Asignador asignador;

    public TwoPhaseOrchestrator(PlanificadorRutasStrategy planificador) {
        this(planificador, new MinCostFlowAsignador());
    }

    public TwoPhaseOrchestrator(PlanificadorRutasStrategy planificador, Asignador asignador) {
        this.planificador = planificador;
        this.asignador = asignador;
    }

    /**
     * Ejecuta el flujo completo de solución en dos fases.
     *
     * @param datos Dataset
     * @param config Configuración
     * @return Solución evaluada
     */
    public Solucion ejecutarFlujoCompleto(Dataset datos, Config_Simulacion config) {
        // ==================== FASE 1: PLANIFICACIÓN DE RUTAS ====================
        long t1 = System.nanoTime();
        Map<String, List<Ruta>> rutasPlanificadas = planificador.planificarRutas(datos, config);
        long ms1 = (System.nanoTime() - t1) / 1_000_000;
        System.err.println("[DEBUG] Fase 1: paquetes=" + rutasPlanificadas.size() + " con rutas, tiempo=" + ms1 + "ms");

        // ==================== FASE 2: ASIGNACIÓN DE ENVÍOS A VUELOS ====================
        long t2 = System.nanoTime();
        Map<String, Vuelo> asignacionesVuelos = asignador.asignar(rutasPlanificadas, datos, config);
        long ms2 = (System.nanoTime() - t2) / 1_000_000;
        System.err.println("[DEBUG] Fase 2: vuelos asignados=" + asignacionesVuelos.size() + ", tiempo=" + ms2 + "ms");

        // ==================== EVALUACIÓN Y GENERACIÓN DE SOLUCIÓN ====================
        // Aquí integramos la evaluación final con la asignación determinística
        long t3 = System.nanoTime();
        Solucion solucion = evaluarSolucionCompleta(rutasPlanificadas, asignacionesVuelos, datos, config);
        long ms3 = (System.nanoTime() - t3) / 1_000_000;
        System.err.println("[DEBUG] Fase 3: evaluación=" + solucion.getRutasAsignadas().size() + " asignadas, tiempo=" + ms3 + "ms");

        return solucion;
    }

    /**
     * Evalúa la solución completa con rutas y asignaciones de vuelos.
     */
    private Solucion evaluarSolucionCompleta(
            Map<String, List<Ruta>> rutasPlanificadas,
            Map<String, Vuelo> asignacionesVuelos,
            Dataset datos,
            Config_Simulacion config
    ) {
        // Por ahora, usamos la evaluación existente
        // En el futuro, esto podría considerar la asignación específica de vuelos
        
        String nombreEstrategia = "TwoPhase-" + planificador.getClass().getSimpleName();
        Solucion solucion = new Solucion(nombreEstrategia);

        // Convertir rutas planificadas a mapa simple (primera ruta para cada paquete)
        Map<String, Ruta> rutasSeleccionadas = new java.util.HashMap<>();
        for (Map.Entry<String, List<Ruta>> entry : rutasPlanificadas.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                rutasSeleccionadas.put(entry.getKey(), entry.getValue().get(0));
            }
        }

        // Usar PlanificacionUtils para evaluación (reemplazar si está disponible)
        solucion = PlanificacionUtils.evaluarAsignacion(nombreEstrategia, rutasSeleccionadas, datos, config);

        // Agregar métricas de la asignación de vuelos
        solucion.setMetrica("vuelosAsignados", asignacionesVuelos.size());
        solucion.setMetrica("paquetesConAsignacionEspecifica", asignacionesVuelos.size());

        return solucion;
    }

    /**
     * Obtiene el nombre del planificador base.
     */
    public String getNombrePlanificador() {
        return planificador.getClass().getSimpleName();
    }
}
