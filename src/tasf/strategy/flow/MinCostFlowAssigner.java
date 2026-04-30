package tasf.strategy.flow;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asigna paquetes a vuelos específicos minimizando el costo total de transporte.
 * Implementa un algoritmo de flujo de costo mínimo con enfoque greedy.
 * 
 * Dadas las rutas planificadas, este asignador selecciona vuelos específicos
 * para cada paquete, considerando:
 * - Capacidad disponible en vuelos
 * - Costo de transporte (horas)
 * - Penalizaciones por incumplimiento de plazos
 * - Balanceo de carga en vuelos
 */
public class MinCostFlowAssigner {
    private static final double COSTO_NO_ASIGNACION = 10000.0;
    private static final double COSTO_FUERA_PLAZO = 2500.0;
    private static final double FACTOR_BALANCEO_CARGA = 100.0;

    /**
     * Asigna paquetes a vuelos específicos dentro de las rutas planificadas,
     * minimizando el costo total del flujo.
     *
     * @param rutasPlanificadas Map de paquete -> rutas candidatas (resultado de metaheurístico)
     * @param datos Dataset con paquetes y vuelos
     * @param config Configuración de simulación
     * @return Map de paquete -> vuelo asignado (selección específica)
     */
    public Map<String, Vuelo> asignarEnviosAVuelos(
            Map<String, List<Ruta>> rutasPlanificadas,
            Dataset datos,
            Config_Simulacion config
    ) {
        Map<String, Vuelo> asignaciones = new HashMap<>();
        Map<String, Integer> cargaVuelos = new HashMap<>();

        System.err.println("[MCF DEBUG] paquetes planificados: " + rutasPlanificadas.size());
        int rutasVacias = 0;
        int rutasConCandidatos = 0;
        for (List<Ruta> rutas : rutasPlanificadas.values()) {
            if (rutas.isEmpty()) rutasVacias++;
            else rutasConCandidatos++;
        }
        System.err.println("[MCF DEBUG] rutas vacías: " + rutasVacias + ", con candidatos: " + rutasConCandidatos);

        // Ordenar paquetes por fecha de creación
        List<Paquete> paquetesOrdenados = new ArrayList<>(datos.getPaquetes());
        paquetesOrdenados.sort((a, b) -> 
            PlanificacionUtils.getCreacionUtc(a, datos, config)
                .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config))
        );

        int intentos = 0;
        int exitos = 0;

        // Asignar cada paquete al vuelo de menor costo disponible
        for (Paquete paquete : paquetesOrdenados) {
            List<Ruta> rutasCandidatas = rutasPlanificadas.getOrDefault(paquete.getId(), Collections.emptyList());
            if (rutasCandidatas.isEmpty()) continue;
            
            intentos++;
            Vuelo mejorVuelo = null;
            double menorCosto = COSTO_NO_ASIGNACION;

            // Buscar el vuelo con menor costo en las rutas disponibles
            for (Ruta ruta : rutasCandidatas) {
                for (Vuelo vuelo : ruta.getVuelos()) {
                    // Verificar capacidad disponible
                    int cargaActual = cargaVuelos.getOrDefault(vuelo.getId(), 0);
                    if (cargaActual + paquete.getCantidad() > vuelo.getCapacidadCarga()) {
                        continue; // Vuelo sin capacidad suficiente
                    }

                    // Calcular costo de asignación
                    double costo = calcularCostoAsignacion(paquete, ruta, vuelo, cargaActual, datos, config);

                    if (costo < menorCosto) {
                        menorCosto = costo;
                        mejorVuelo = vuelo;
                    }
                }
            }

            // Realizar asignación si encontró vuelo disponible
            if (mejorVuelo != null) {
                asignaciones.put(paquete.getId(), mejorVuelo);
                cargaVuelos.put(
                    mejorVuelo.getId(),
                    cargaVuelos.getOrDefault(mejorVuelo.getId(), 0) + paquete.getCantidad()
                );
                exitos++;
            }
            
            if (intentos <= 10 || intentos % 1000 == 0) {
                System.err.println("[MCF DEBUG] paquete " + paquete.getId() + " intentos=" + intentos + " asignados=" + asignaciones.size());
            }
        }
        
        System.err.println("[MCF DEBUG] resultado final: intentos=" + intentos + " éxitos=" + exitos + " asignaciones=" + asignaciones.size());
        return asignaciones;
    }

    /**
     * Alias orientado a la interfaz Strategy Asignador.
     */
    public Map<String, Vuelo> asignar(
            Map<String, List<Ruta>> rutasPlanificadas,
            Dataset datos,
            Config_Simulacion config
    ) {
        return asignarEnviosAVuelos(rutasPlanificadas, datos, config);
    }

    /**
     * Calcula el costo de asignar un paquete a un vuelo específico.
     */
    private double calcularCostoAsignacion(
            Paquete paquete,
            Ruta ruta,
            Vuelo vuelo,
            int cargaActualVuelo,
            Dataset datos,
            Config_Simulacion config
    ) {
        double costo = 0.0;

        // Costo 1: Horas de transporte
        LocalDateTime creacionUtc = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
        double horasTransporte = ruta.getHorasTotalesDesde(creacionUtc);
        costo += horasTransporte;

        // Costo 2: Penalización por incumplimiento de plazo
        if (estaFueraDePlazo(paquete, ruta, datos, config)) {
            costo += COSTO_FUERA_PLAZO;
        }

        // Costo 3: Penalización por balanceo de carga (evitar vuelos muy llenos)
        double ocupacion = (double) (cargaActualVuelo + paquete.getCantidad()) / vuelo.getCapacidadCarga();
        costo += ocupacion * FACTOR_BALANCEO_CARGA;

        return costo;
    }

    /**
     * Verifica si un paquete llegará fuera de su plazo de entrega.
     */
    private boolean estaFueraDePlazo(
            Paquete paquete,
            Ruta ruta,
            Dataset datos,
            Config_Simulacion config
    ) {
        LocalDateTime creacionUtc = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
        Duration plazo = PlanificacionUtils.getPlazoObjetivo(paquete, datos, config);
        LocalDateTime limiteEntrega = creacionUtc.plus(plazo);
        return ruta.getLlegadaUtc().isAfter(limiteEntrega);
    }
}
