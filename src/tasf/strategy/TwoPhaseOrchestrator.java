package tasf.strategy;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.core.Solucion;
import tasf.model.Ruta;
import tasf.strategy.flow.Asignador;
import tasf.strategy.flow.MinCostFlowAsignador;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Orquestador del flujo de solución en dos fases:
 * 
 * Fase 1: Planificación de Rutas
 * - Usa un metaheurístico (ALNS, etc.)
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

        int totalPaquetes = datos.getPaquetes().size();
        int rutasPlanificadasFase1 = rutasSeleccionadas.size();
        int paquetesSinRutaFase1 = totalPaquetes - rutasPlanificadasFase1;

        long t2 = System.nanoTime();
        Map<String, Ruta> rutasAceptadas = asignador.asignar(rutasSeleccionadas, datos, config);
        long ms2 = (System.nanoTime() - t2) / 1_000_000;

        // Calcular métricas comparando claves de los mapas
        Set<String> conRutaFase1 = rutasSeleccionadas.keySet();
        Set<String> conRutaFase2 = rutasAceptadas.keySet();

        // Paquetes que mantienen ruta (puede ser la misma o una reemplazada)
        Set<String> mantienenRuta = new HashSet<>(conRutaFase1);
        mantienenRuta.retainAll(conRutaFase2);

        // Paquetes con ruta de Fase 1 rechazada (tenían en Fase 1, no tienen en Fase 2)
        Set<String> rechazados = new HashSet<>(conRutaFase1);
        rechazados.removeAll(conRutaFase2);

        // Paquetes nuevos asignados por Fase 2 (no tenían en Fase 1, tienen en Fase 2)
        Set<String> nuevosAsignados = new HashSet<>(conRutaFase2);
        nuevosAsignados.removeAll(conRutaFase1);

        int rutasAceptadasFase2 = rutasAceptadas.size();
        int rutasRechazadasFase2 = rechazados.size();
        int paquetesNuevosFase2 = nuevosAsignados.size();

        long t3 = System.nanoTime();
        Solucion solucion = evaluarSolucionCompleta(rutasAceptadas, datos, config);
        long ms3 = (System.nanoTime() - t3) / 1_000_000;

        solucion.setMetrica("msFase1Rutas", ms1);
        solucion.setMetrica("msFase2Asignacion", ms2);
        solucion.setMetrica("msFase3Evaluacion", ms3);

        solucion.setMetrica("totalPaquetes", totalPaquetes);
        solucion.setMetrica("rutasPlanificadasFase1", rutasPlanificadasFase1);
        solucion.setMetrica("paquetesSinRutaFase1", paquetesSinRutaFase1);
        solucion.setMetrica("rutasAceptadasFase2", rutasAceptadasFase2);
        solucion.setMetrica("rutasRechazadasFase2", rutasRechazadasFase2);
        solucion.setMetrica("paquetesNuevosFase2", paquetesNuevosFase2);

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
        return PlanificacionUtils.evaluarAsignacion(nombreEstrategia, rutasAceptadas, datos, config);
    }
}
