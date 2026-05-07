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

    private static volatile Map<String, List<Ruta>> CACHE_GLOBAL_RUTAS = new ConcurrentHashMap<>();

    public static void limpiarCacheGlobal() {
        CACHE_GLOBAL_RUTAS = new ConcurrentHashMap<>();
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
        List<Paquete> paquetes = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (propuesta.containsKey(p.getId())) {
                paquetes.add(p);
            }
        }
        // Priorizar paquetes con rutas mas restringidas (menos vuelos), luego por creacion
        paquetes.sort((a, b) -> {
            Ruta ra = propuesta.get(a.getId());
            Ruta rb = propuesta.get(b.getId());
            int na = ra != null ? ra.getVuelos().size() : Integer.MAX_VALUE;
            int nb = rb != null ? rb.getVuelos().size() : Integer.MAX_VALUE;
            if (na != nb) return Integer.compare(na, nb);
            return getCreacionUtc(a, datos, config).compareTo(getCreacionUtc(b, datos, config));
        });

        double horasAcumuladas = 0.0;
        int entregados = 0;

        for (Paquete paquete : paquetes) {
            Ruta ruta = propuesta.get(paquete.getId());
            if (ruta == null) continue;

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

        // Marcar como no asignados los paquetes que nunca tuvieron ruta en la propuesta
        for (Paquete p : datos.getPaquetes()) {
            if (!propuesta.containsKey(p.getId()) && !solucion.getPaquetesNoAsignados().contains(p.getId())) {
                solucion.marcarNoAsignado(p.getId(), false);
            }
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
        solucion.setMetrica("eventosColapso", solucion.getEventosColapso());
        return solucion;
    }

    /**
     * Score local y rápido para un solo paquete+ruta. NO itera todo el dataset.
     * Penaliza: tardanza proporcional a minutos de retraso, escalas, horas de ruta.
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
        LocalDateTime limite = creacion.plus(getPlazoObjetivo(paquete, datos, config));
        double tardanzaMin = Math.max(0, Duration.between(limite, ruta.getLlegadaUtc()).toMinutes());
        // Penalización fuerte por tardanza: cada minuto tarde = 50 puntos
        double costoTardanza = tardanzaMin * 500.0;
        double costoEscalas = ruta.getCantidadSaltos() * 500.0;
        double horasRuta = ruta.getHorasTotalesDesde(creacion);
        return costoTardanza + costoEscalas + horasRuta;
    }

    /**
     * Score con penalización de congestión: considera capacidad de aeropuertos y vuelos.
     * Penaliza MASIVAMENTE el tiempo de espera en aeropuertos (cuello de botella principal = 87% de fallos).
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
        int capAeropuerto = aeropuertoActual.getCapacidadMaxima();
        List<Vuelo> vuelos = ruta.getVuelos();

        for (int i = 0; i < vuelos.size(); i++) {
            Vuelo vuelo = vuelos.get(i);
            // Penalización EXTREMA por espera en aeropuerto (87% de los fallos son por esto)
            LocalDateTime inicioEspera = instante;
            if (inicioEspera.isBefore(vuelo.getSalidaUtc())) {
                LocalDateTime hora = inicioEspera.truncatedTo(ChronoUnit.HOURS);
                LocalDateTime fin = vuelo.getSalidaUtc();
                long horasEspera = Duration.between(inicioEspera, vuelo.getSalidaUtc()).toHours();
                while (hora.isBefore(fin)) {
                    int ocupacion = estado.getOcupacionHora(aeropuertoActual.getCodigoOACI(), hora);
                    double ratio = (double) ocupacion / Math.max(1, capAeropuerto);
                    // Penalización EXPONENCIAL por congestión (5^ratio)
                    if (ratio > 0.8) costo += Math.pow(5, ratio * 10) * cantidad;
                    // Penalización CÚBICA × cantidad × horas de espera
                    costo += Math.pow(ratio, 3) * 500000.0 * cantidad;
                    hora = hora.plusHours(1);
                }
                // Penalización adicional LINEAL por duración de espera
                costo += horasEspera * 10000.0 * cantidad;
            }

            // Penalizacion por carga del vuelo (exponencial: ratio^4, muy agresivo)
            int cargaActual = estado.getCargaVuelo(vuelo.getId());
            double ratioVuelo = (double) cargaActual / Math.max(1, vuelo.getCapacidadCarga());
            costo += Math.pow(ratioVuelo, 4) * 50000.0 * cantidad;

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
        // Buscar MUCHAS rutas por par OD para máxima diversidad temporal
        if (!paresPendientes.isEmpty()) {
            int hilos = Math.min(Runtime.getRuntime().availableProcessors(), paresPendientes.size());
            ExecutorService pool = Executors.newFixedThreadPool(hilos);
            final int total = paresPendientes.size();
            final long t0 = System.nanoTime();

            // Buscar muchas más rutas que las necesarias: filtrar después por paquete
            int rutasPorPar = Math.max(config.getMaxRutasPorPaquete() * 20, 500);

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
                            rutasPorPar,
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

        // Paso 3: Para CADA paquete, filtrar rutas cacheadas por su tiempo de creacion
        // y seleccionar hasta maxRutasPorPaquete
        for (Paquete paquete : datos.getPaquetes()) {
            LocalDateTime creacionUtc = getCreacionUtc(paquete, datos, config);
            String key = paquete.getOrigenOACI() + "|" + paquete.getDestinoOACI();
            List<Ruta> cacheadas = CACHE_GLOBAL_RUTAS.get(key);

            if (cacheadas == null || cacheadas.isEmpty()) {
                candidatos.put(paquete.getId(), List.of());
                continue;
            }

            LocalDateTime finVentana = creacionUtc.plus(config.getHorizonteBusqueda());
            Duration plazo = getPlazoObjetivo(paquete, datos, config);
            LocalDateTime limiteEntrega = creacionUtc.plus(plazo);
            
            List<Ruta> filtradas = new ArrayList<>();
            for (Ruta ruta : cacheadas) {
                if (ruta.getSalidaUtc().isBefore(creacionUtc)) continue;
                if (ruta.getSalidaUtc().isAfter(finVentana)) break;
if (ruta.getLlegadaUtc().isAfter(limiteEntrega)) continue; // Filtro por plazo de entrega
                if (config.getFinSimulacionUtcExclusivo() != null
                        && estaFueraDeVentanaSimulacion(ruta, config)) continue;
                filtradas.add(ruta);
                if (filtradas.size() >= config.getMaxRutasPorPaquete()) break;
            }

            // Si no hay rutas dentro del deadline, NO usar rutas tardías
            if (filtradas.isEmpty()) {
                candidatos.put(paquete.getId(), List.of());
                continue;
            }
            candidatos.put(paquete.getId(), filtradas);
        }

        return candidatos;
    }
}
