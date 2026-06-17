package tasf.strategy.flow;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.model.Paquete;
import tasf.model.Ruta;

import java.time.Duration;
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

        Map<String, List<Ruta>> candidatos = construirCandidatosRutas(datos, config);
        List<Paquete> paquetesOrdenados = new ArrayList<>(datos.getPaquetes());
        paquetesOrdenados.sort((a, b) -> {
            int na = candidatos.getOrDefault(a.getId(), List.of()).size();
            int nb = candidatos.getOrDefault(b.getId(), List.of()).size();
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        for (Paquete paquete : paquetesOrdenados) {
            Ruta rutaSeleccionada = rutasPlanificadas.get(paquete.getId());
            if (rutaSeleccionada == null) {
                continue;
            }

            LocalDateTime creacionUtc = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
            boolean factible = estado.reservarRutaSiFactible(paquete, rutaSeleccionada, creacionUtc, datos, config);
            if (factible) {
                asignaciones.put(paquete.getId(), rutaSeleccionada);
            } else {
                Ruta rutaAlternativa = buscarRutaAlternativa(paquete, candidatos, estado, creacionUtc, datos, config);
                if (rutaAlternativa != null) {
                    estado.reservarRutaSiFactible(paquete, rutaAlternativa, creacionUtc, datos, config);
                    asignaciones.put(paquete.getId(), rutaAlternativa);
                }
            }
        }

        asignaciones = intentarAsignarNoAsignados(asignaciones, candidatos, estado, datos, config);
        return asignaciones;
    }

    private Ruta buscarRutaAlternativa(
            Paquete paquete,
            Map<String, List<Ruta>> candidatos,
            EstadoOperacional estado,
            LocalDateTime creacionUtc,
            Dataset datos,
            Config_Simulacion config
    ) {
        List<Ruta> rutasCandidatas = candidatos.getOrDefault(paquete.getId(), List.of());
        if (rutasCandidatas.isEmpty()) return null;

        List<Ruta> rutasEnPlazo = new ArrayList<>();
        List<Ruta> rutasFueraPlazo = new ArrayList<>();

        for (Ruta ruta : rutasCandidatas) {
            boolean fueraDePlazo = PlanificacionUtils.estaFueraDePlazo(paquete, ruta, datos, config);
            if (fueraDePlazo) {
                rutasFueraPlazo.add(ruta);
            } else {
                rutasEnPlazo.add(ruta);
            }
        }

        List<Ruta> rutasAPriorizar = rutasEnPlazo.isEmpty() ? rutasFueraPlazo : rutasEnPlazo;

        Ruta mejorRuta = null;
        double mejorScore = Double.POSITIVE_INFINITY;

        for (Ruta ruta : rutasAPriorizar) {
            EstadoOperacional prueba = estado.copia();
            if (prueba.reservarRutaSiFactible(paquete, ruta, creacionUtc, datos, config)) {
                double score = PlanificacionUtils.evaluarRutaIndividualLight(paquete, ruta, estado, datos, config);
                if (score < mejorScore) {
                    mejorScore = score;
                    mejorRuta = ruta;
                }
            }
        }

        return mejorRuta;
    }

    private Map<String, Ruta> intentarAsignarNoAsignados(
            Map<String, Ruta> asignaciones,
            Map<String, List<Ruta>> candidatos,
            EstadoOperacional estado,
            Dataset datos,
            Config_Simulacion config
    ) {
        List<Paquete> sinAsignar = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (!asignaciones.containsKey(p.getId()) && !candidatos.getOrDefault(p.getId(), List.of()).isEmpty()) {
                sinAsignar.add(p);
            }
        }

        if (sinAsignar.isEmpty()) return asignaciones;

        sinAsignar.sort((a, b) -> {
            int na = candidatos.getOrDefault(a.getId(), List.of()).size();
            int nb = candidatos.getOrDefault(b.getId(), List.of()).size();
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        for (Paquete paquete : sinAsignar) {
            LocalDateTime creacionUtc = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
            Ruta ruta = buscarRutaAlternativa(paquete, candidatos, estado, creacionUtc, datos, config);
            if (ruta != null) {
                estado.reservarRutaSiFactible(paquete, ruta, creacionUtc, datos, config);
                asignaciones.put(paquete.getId(), ruta);
            }
        }

        return asignaciones;
    }

    /**
     * Construye conjunto de rutas candidatas con enfoque híbrido:
     * - Usa cache global de Fase 1 cuando tiene rutas suficientes
     * - Hace búsqueda individual para paquetes fuera de la ventana cacheada
     */
    private Map<String, List<Ruta>> construirCandidatosRutas(
            Dataset datos,
            Config_Simulacion config
    ) {
        Map<String, List<Ruta>> candidatos = new HashMap<>();
        RouteFinder finder = new RouteFinder(datos);
        
        int minRutasCache = 3;
        int maxRutasFallback = 50;
        
        for (Paquete p : datos.getPaquetes()) {
            LocalDateTime creacionUtc = PlanificacionUtils.getCreacionUtc(p, datos, config);
            String key = p.getOrigenOACI() + "|" + p.getDestinoOACI();
            
            List<Ruta> cacheadas = PlanificacionUtils.obtenerRutasCache(key);
            List<Ruta> filtradas = filtrarRutasPorPaquete(cacheadas, creacionUtc, p, datos, config);
            
            if (filtradas.size() >= minRutasCache) {
                candidatos.put(p.getId(), filtradas);
            } else {
                List<Ruta> rutasIndividuales = finder.buscarRutas(
                        p.getOrigenOACI(), p.getDestinoOACI(), creacionUtc,
                        maxRutasFallback, config.getMaxEscalas(), 
                        config.getMinimaConexion(), config.getHorizonteBusqueda()
                );
                List<Ruta> filtradasIndividuales = filtrarRutasPorPaquete(
                        rutasIndividuales, creacionUtc, p, datos, config
                );
                candidatos.put(p.getId(), filtradasIndividuales);
            }
        }
        
        return candidatos;
    }
    
    private List<Ruta> filtrarRutasPorPaquete(
            List<Ruta> rutas,
            LocalDateTime creacionUtc,
            Paquete paquete,
            Dataset datos,
            Config_Simulacion config
    ) {
        if (rutas == null || rutas.isEmpty()) {
            return List.of();
        }
        
        LocalDateTime finVentana = creacionUtc.plus(config.getHorizonteBusqueda());
        Duration plazo = PlanificacionUtils.getPlazoObjetivo(paquete, datos, config);
        LocalDateTime limiteEntrega = creacionUtc.plus(plazo);
        
        List<Ruta> filtradas = new ArrayList<>();
        for (Ruta ruta : rutas) {
            if (ruta.getSalidaUtc().isBefore(creacionUtc)) continue;
            if (ruta.getSalidaUtc().isAfter(finVentana)) break;
            if (ruta.getLlegadaUtc().isAfter(limiteEntrega)) continue;
            filtradas.add(ruta);
            if (filtradas.size() >= config.getMaxRutasPorPaquete()) break;
        }
        
        return filtradas;
    }
}
