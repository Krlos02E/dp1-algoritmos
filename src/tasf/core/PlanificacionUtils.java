package tasf.core;

import tasf.config.Config_Simulacion;
import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PlanificacionUtils {
    private PlanificacionUtils() {
    }

    private static volatile Map<String, List<Ruta>> CACHE_GLOBAL_RUTAS = new HashMap<>();

    public static void limpiarCacheGlobal() {
        CACHE_GLOBAL_RUTAS = new HashMap<>();
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

    /**
     * Score local y rápido para un solo paquete+ruta. NO itera todo el dataset.
     * Úsese dentro de bucles metaheurísticos donde evaluarAsignacion() es prohibitivo.
     *
     * Penaliza: fuera de plazo (2500), colapso potencial (500), horas totales, escalas extras.
     */
    public static double evaluarRutaIndividual(
            Paquete paquete,
            Ruta ruta,
            Dataset datos,
            Config_Simulacion config
    ) {
        if (ruta == null) {
            return 10000.0;
        }
        if (estaFueraDeVentanaSimulacion(ruta, config)) {
            return 10000.0;
        }
        LocalDateTime creacion = getCreacionUtc(paquete, datos, config);
        double horas = ruta.getHorasTotalesDesde(creacion);
        double costoFueraPlazo = estaFueraDePlazo(paquete, ruta, datos, config) ? 2500.0 : 0.0;
        double costoEscalas = ruta.getCantidadSaltos() * 500.0;
        return costoFueraPlazo + costoEscalas + horas;
    }

    /**
     * Score con penalización de congestión: considera capacidad de aeropuertos y vuelos.
     * Penalización lineal × ratio para efecto desde el inicio.
     */
    public static double evaluarRutaIndividual(
            Paquete paquete,
            Ruta ruta,
            EstadoOperacional estado,
            Dataset datos,
            Config_Simulacion config
    ) {
        double costo = evaluarRutaIndividual(paquete, ruta, datos, config);
        if (ruta == null || estado == null) return costo;

        Aeropuerto aeropuertoActual = datos.getAeropuerto(paquete.getOrigenOACI());
        if (aeropuertoActual == null) {
            aeropuertoActual = datos.getAeropuerto(config.getAeropuertoHub());
        }
        if (aeropuertoActual == null) return costo;

        LocalDateTime instante = getCreacionUtc(paquete, datos, config);
        int cantidad = paquete.getCantidad();
        List<Vuelo> vuelos = ruta.getVuelos();

        for (int i = 0; i < vuelos.size(); i++) {
            Vuelo vuelo = vuelos.get(i);
            // Penalización por espera en aeropuerto
            LocalDateTime inicioEspera = (i == 0) ? instante : instante;
            if (inicioEspera.isBefore(vuelo.getSalidaUtc())) {
                LocalDateTime hora = inicioEspera.truncatedTo(ChronoUnit.HOURS);
                LocalDateTime fin = vuelo.getSalidaUtc();
                while (hora.isBefore(fin)) {
                    int ocupacion = estado.getOcupacionHora(aeropuertoActual.getCodigoOACI(), hora);
                    double ratio = (double) ocupacion / Math.max(1, aeropuertoActual.getCapacidadMaxima());
                    // Lineal × peso × cantidad
                    costo += ratio * 2000.0 * cantidad;
                    hora = hora.plusHours(1);
                }
            }

            // Penalización por carga del vuelo (lineal con factor alto)
            int cargaActual = estado.getCargaVuelo(vuelo.getId());
            double ratioVuelo = (double) cargaActual / Math.max(1, vuelo.getCapacidadCarga());
            costo += ratioVuelo * 3000.0 * cantidad;

            // Avanzar al siguiente punto
            aeropuertoActual = vuelo.getDestino();
            instante = vuelo.getLlegadaUtc();
        }

        return costo;
    }

    public static Map<String, List<Ruta>> construirCandidatosRutas(
            Dataset datos,
            Config_Simulacion config,
            RouteFinder finder
    ) {
        Map<String, List<Ruta>> candidatos = new HashMap<>();

        // Paso 1: Identificar pares origen-destino que necesitan búsqueda
        Set<String> paresPendientes = new HashSet<>();
        Map<String, LocalDateTime> primerCreacionPorPar = new HashMap<>();
        for (Paquete paquete : datos.getPaquetes()) {
            String key = paquete.getOrigenOACI() + "|" + paquete.getDestinoOACI();
            if (!CACHE_GLOBAL_RUTAS.containsKey(key)) {
                paresPendientes.add(key);
                primerCreacionPorPar.putIfAbsent(key, getCreacionUtc(paquete, datos, config));
            }
        }

        // Paso 2: Buscar rutas pendientes en paralelo
        if (!paresPendientes.isEmpty()) {
            int hilos = Math.min(Runtime.getRuntime().availableProcessors(), paresPendientes.size());
            ExecutorService pool = Executors.newFixedThreadPool(hilos);
            final int total = paresPendientes.size();
            final long t0 = System.nanoTime();

            List<String> paresLista = new ArrayList<>(paresPendientes);
            for (int i = 0; i < paresLista.size(); i++) {
                final int idx = i;
                final String par = paresLista.get(i);
                pool.submit(() -> {
                    String[] partes = par.split("\\|");
                    LocalDateTime creacionUtc = primerCreacionPorPar.getOrDefault(par,
                            LocalDateTime.of(2026, 1, 1, 0, 0));
                    List<Ruta> rutas = finder.buscarRutas(
                            partes[0], partes[1], creacionUtc,
                            config.getMaxRutasPorPaquete(),
                            config.getMaxEscalas(),
                            config.getMinimaConexion(),
                            config.getHorizonteBusqueda()
                    );
                    if (config.getFinSimulacionUtcExclusivo() != null) {
                        List<Ruta> filtradas = new ArrayList<>();
                        for (Ruta r : rutas) {
                            if (!estaFueraDeVentanaSimulacion(r, config)) filtradas.add(r);
                        }
                        rutas = filtradas;
                    }
                    CACHE_GLOBAL_RUTAS.put(par, rutas);

                    int completados = (idx + 1);
                    if (completados % 20 == 0 || completados == total) {
                        long ms = (System.nanoTime() - t0) / 1_000_000;
                        System.out.println(String.format(Locale.ROOT,
                                "  [RUTAS] %d/%d pares encontrados [%dms]",
                                completados, total, ms));
                    }
                });
            }
            pool.shutdown();
            try { pool.awaitTermination(10, TimeUnit.MINUTES); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // Paso 3: Filtrar rutas cacheadas por tiempo de cada paquete
        for (Paquete paquete : datos.getPaquetes()) {
            LocalDateTime creacionUtc = getCreacionUtc(paquete, datos, config);
            String key = paquete.getOrigenOACI() + "|" + paquete.getDestinoOACI();
            List<Ruta> cacheadas = CACHE_GLOBAL_RUTAS.get(key);

            if (cacheadas == null || cacheadas.isEmpty()) {
                candidatos.put(paquete.getId(), List.of());
                continue;
            }

            LocalDateTime finVentana = creacionUtc.plus(config.getHorizonteBusqueda());
            List<Ruta> filtradas = new ArrayList<>();
            for (Ruta ruta : cacheadas) {
                if (ruta.getSalidaUtc().isBefore(creacionUtc)) continue;
                if (ruta.getSalidaUtc().isAfter(finVentana)) break;
                if (config.getFinSimulacionUtcExclusivo() != null
                        && estaFueraDeVentanaSimulacion(ruta, config)) continue;
                filtradas.add(ruta);
            }
            candidatos.put(paquete.getId(), filtradas);
        }

        return candidatos;
    }
}
