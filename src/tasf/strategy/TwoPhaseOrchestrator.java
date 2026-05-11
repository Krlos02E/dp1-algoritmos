package tasf.strategy;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.core.Solucion;
import tasf.model.Ruta;
import tasf.strategy.flow.Asignador;
import tasf.strategy.flow.MinCostFlowAsignador;

import java.util.Map;

/**
 * Orquestador del flujo de solución en dos fases:
 * 
 * Fase 1: Planificación de Rutas
 * - Usa un metaheurístico (ACO, ALNS, etc.)
 * - Devuelve la ruta óptima seleccionada para cada paquete
 * 
 * Fase 2: Asignación de Envíos a Vuelos
 * - Usa una asignación determinista sobre las rutas elegidas
 * - Reserva la ruta completa paquete -> ruta -> vuelos
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
        long t1 = System.nanoTime();
        Map<String, Ruta> rutasSeleccionadas = planificador.planificarRutas(datos, config);
        long ms1 = (System.nanoTime() - t1) / 1_000_000;

        long t2 = System.nanoTime();
        Map<String, Ruta> rutasAceptadas = asignador.asignar(rutasSeleccionadas, datos, config);
        long ms2 = (System.nanoTime() - t2) / 1_000_000;

        long t3 = System.nanoTime();
        Solucion solucion = evaluarSolucionCompleta(rutasAceptadas, datos, config);
        long ms3 = (System.nanoTime() - t3) / 1_000_000;

        solucion.setMetrica("msFase1Rutas", ms1);
        solucion.setMetrica("msFase2Asignacion", ms2);
        solucion.setMetrica("msFase3Evaluacion", ms3);

        return solucion;
    }

    /**
     * Evalúa la solución completa con rutas y asignaciones de vuelos.
     */
    private Solucion evaluarSolucionCompleta(
            Map<String, Ruta> rutasAceptadas,
            Dataset datos,
            Config_Simulacion config
    ) {
        String nombreEstrategia = "TwoPhase-" + planificador.getClass().getSimpleName();
        Solucion solucion = PlanificacionUtils.evaluarAsignacion(nombreEstrategia, rutasAceptadas, datos, config);
        solucion.setMetrica("rutasAceptadasFase2", rutasAceptadas.size());
        solucion.setMetrica("paquetesConRutaSeleccionada", rutasAceptadas.size());

        return solucion;
    }
}
