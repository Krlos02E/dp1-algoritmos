package tasf.core;

import tasf.config.Config_Simulacion;
import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Ruta;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlanificacionUtils {
    private PlanificacionUtils() {
    }

    public static LocalDateTime getCreacionUtc(Paquete paquete, Dataset datos, Config_Simulacion config) {
        Aeropuerto origen = datos.getAeropuerto(paquete.getOrigenOACI());
        if (origen == null) {
            Aeropuerto hub = datos.getAeropuerto(config.getAeropuertoHub());
            if (hub == null) {
                throw new IllegalStateException(
                        "No existe aeropuerto de origen ni hub para paquete: " + paquete.getId()
                );
            }
            return paquete.getInstanteCreacionUtc(hub);
        }
        return paquete.getInstanteCreacionUtc(origen);
    }

    public static Duration getPlazoObjetivo(Paquete paquete, Dataset datos, Config_Simulacion config) {
        Aeropuerto origen = datos.getAeropuerto(paquete.getOrigenOACI());
        Aeropuerto destino = datos.getAeropuerto(paquete.getDestinoOACI());
        if (origen == null || destino == null) {
            return config.getPlazoIntercontinental();
        }
        return origen.getContinente() == destino.getContinente()
                ? config.getPlazoMismoContinente()
                : config.getPlazoIntercontinental();
    }

    public static boolean estaFueraDePlazo(Paquete paquete, Ruta ruta, Dataset datos, Config_Simulacion config) {
        LocalDateTime creacionUtc = getCreacionUtc(paquete, datos, config);
        Duration plazo = getPlazoObjetivo(paquete, datos, config);
        LocalDateTime limite = creacionUtc.plus(plazo);
        return ruta.getLlegadaUtc().isAfter(limite);
    }

    public static boolean estaFueraDeVentanaSimulacion(Ruta ruta, Config_Simulacion config) {
        LocalDateTime finExclusivo = config.getFinSimulacionUtcExclusivo();
        return finExclusivo != null && !ruta.getLlegadaUtc().isBefore(finExclusivo);
    }

    public static EstadoOperacional construirEstadoConAsignaciones(
            Map<String, Ruta> propuesta,
            Dataset datos,
            Config_Simulacion config
    ) {
        EstadoOperacional estado = new EstadoOperacional();
        List<Paquete> ordenados = new ArrayList<>(datos.getPaquetes());
        ordenados.sort(Comparator.comparing(p -> getCreacionUtc(p, datos, config)));

        for (Paquete paquete : ordenados) {
            Ruta ruta = propuesta.get(paquete.getId());
            if (ruta == null) {
                continue;
            }
            LocalDateTime creacionUtc = getCreacionUtc(paquete, datos, config);
            estado.reservarRutaSiFactible(paquete, ruta, creacionUtc, datos, config);
        }

        return estado;
    }

    public static Solucion evaluarAsignacion(
            String estrategia,
            Map<String, Ruta> propuesta,
            Dataset datos,
            Config_Simulacion config
    ) {
        Solucion solucion = new Solucion(estrategia);
        EstadoOperacional estado = new EstadoOperacional();
        List<Paquete> ordenados = new ArrayList<>(datos.getPaquetes());
        ordenados.sort(Comparator.comparing(p -> getCreacionUtc(p, datos, config)));

        double horasAcumuladas = 0.0;
        int entregados = 0;

        for (Paquete paquete : ordenados) {
            Ruta ruta = propuesta.get(paquete.getId());
            if (ruta == null) {
                solucion.marcarNoAsignado(paquete.getId(), false);
                continue;
            }

            if (estaFueraDeVentanaSimulacion(ruta, config)) {
                solucion.marcarNoAsignado(paquete.getId(), false);
                continue;
            }

            LocalDateTime creacionUtc = getCreacionUtc(paquete, datos, config);
            boolean reservado = estado.reservarRutaSiFactible(paquete, ruta, creacionUtc, datos, config);
            if (!reservado) {
                solucion.marcarNoAsignado(paquete.getId(), true);
                continue;
            }

            boolean fueraDePlazo = estaFueraDePlazo(paquete, ruta, datos, config);
            solucion.asignar(paquete.getId(), ruta, fueraDePlazo);
            horasAcumuladas += ruta.getHorasTotalesDesde(creacionUtc);
            entregados++;
        }

        double horasPromedio = entregados == 0 ? 0.0 : horasAcumuladas / entregados;
        solucion.setHorasPromedioEntrega(horasPromedio);

        int noAsignados = solucion.getPaquetesNoAsignados().size();
        double costo =
                (noAsignados * 10000.0)
                        + (solucion.getMaletasFueraDePlazo() * 2500.0)
                        + (solucion.getEventosColapso() * 5000.0)
                        + horasAcumuladas;
        solucion.setCostoTotal(costo);
        solucion.setMetrica("paquetesNoAsignados", noAsignados);
        return solucion;
    }

    public static Map<String, List<Ruta>> construirCandidatosRutas(
            Dataset datos,
            Config_Simulacion config,
            RouteFinder finder
    ) {
        Map<String, List<Ruta>> candidatos = new HashMap<>();
        Map<String, List<Ruta>> cacheConsulta = new HashMap<>();

        for (Paquete paquete : datos.getPaquetes()) {
            LocalDateTime creacionUtc = getCreacionUtc(paquete, datos, config);
            LocalDateTime bucket = creacionUtc.truncatedTo(ChronoUnit.HOURS);
            String keyCache = paquete.getOrigenOACI() + "|" + paquete.getDestinoOACI() + "|" + bucket;

            List<Ruta> rutas = cacheConsulta.get(keyCache);
            if (rutas == null) {
                rutas = finder.buscarRutas(
                        paquete.getOrigenOACI(),
                        paquete.getDestinoOACI(),
                        creacionUtc,
                        config.getMaxRutasPorPaquete(),
                        config.getMaxEscalas(),
                        config.getMinimaConexion(),
                        config.getHorizonteBusqueda()
                );

                if (config.getFinSimulacionUtcExclusivo() != null) {
                    List<Ruta> filtradas = new ArrayList<>();
                    for (Ruta ruta : rutas) {
                        if (!estaFueraDeVentanaSimulacion(ruta, config)) {
                            filtradas.add(ruta);
                        }
                    }
                    rutas = filtradas;
                }

                cacheConsulta.put(keyCache, rutas);
            }
            candidatos.put(paquete.getId(), rutas);
        }
        return candidatos;
    }
}
