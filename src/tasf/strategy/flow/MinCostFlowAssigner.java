package tasf.strategy.flow;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.model.Paquete;
import tasf.model.Ruta;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asigna paquetes a las rutas seleccionadas por la fase 1.
 * 
 * La fase 2 es deterministica: toma la ruta elegida para cada paquete y
 * la acepta solo si es factible con la ocupacion actual y las restricciones
 * operativas del simulador.
 */
public class MinCostFlowAssigner {
    /**
     * Asigna paquetes a rutas seleccionadas dentro del estado operacional actual.
     *
     * @param rutasPlanificadas Map de paquete -> ruta seleccionada (resultado de la fase 1)
     * @param datos Dataset con paquetes y vuelos
     * @param config Configuración de simulación
     * @return Map de paquete -> ruta aceptada por la fase deterministica
     */
    public Map<String, Ruta> asignarEnviosAVuelos(
            Map<String, Ruta> rutasPlanificadas,
            Dataset datos,
            Config_Simulacion config
    ) {
        Map<String, Ruta> asignaciones = new HashMap<>();
        EstadoOperacional estado = new EstadoOperacional();

        // Ordenar paquetes por fecha de creación
        List<Paquete> paquetesOrdenados = new ArrayList<>(datos.getPaquetes());
        paquetesOrdenados.sort((a, b) -> 
            PlanificacionUtils.getCreacionUtc(a, datos, config)
                .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config))
        );

        int intentos = 0;
        int exitos = 0;

        // Aceptar cada ruta seleccionada si sigue siendo factible con la carga acumulada.
        for (Paquete paquete : paquetesOrdenados) {
            Ruta rutaSeleccionada = rutasPlanificadas.get(paquete.getId());
            if (rutaSeleccionada == null) {
                continue;
            }

            intentos++;
            LocalDateTime creacionUtc = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
            boolean factible = estado.reservarRutaSiFactible(paquete, rutaSeleccionada, creacionUtc, datos, config);
            if (factible) {
                asignaciones.put(paquete.getId(), rutaSeleccionada);
                exitos++;
            }
        }
        
        return asignaciones;
    }

    /**
     * Alias orientado a la interfaz Strategy Asignador.
     */
    public Map<String, Ruta> asignar(
            Map<String, Ruta> rutasPlanificadas,
            Dataset datos,
            Config_Simulacion config
    ) {
        return asignarEnviosAVuelos(rutasPlanificadas, datos, config);
    }
}
