package tasf.strategy.alns;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.core.Solucion;
import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;
import tasf.strategy.PlanificadorStrategy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * ALNS con Matriz de Capacidad de Aeropuerto.
 *
 * La matriz E[airport][hourSlot] rastrea ocupación horaria de cada aeropuerto.
 * Los operadores de destroy atacan los slots más congestionados para forzar
 * redistribución de carga.
 */
public class ALNS_Strategy implements PlanificadorStrategy {

    private static final List<Ruta> EMPTY_RUTA_LIST = List.of();

    private static final int RUPTURA_RANDOM = 0;
    private static final int RUPTURA_WORST_DELAY = 1;
    private static final int RUPTURA_CONGESTION = 2;
    private static final int REPARACION_GREEDY = 0;
    private static final int REPARACION_REGRET = 1;

    private final Random random;
    private final double[] pesosRuptura = {1.0, 1.0, 1.0};
    private final double[] pesosReparacion = {1.0, 1.0};
    private final double[] puntajesRuptura = {0.0, 0.0, 0.0};
    private final double[] puntajesReparacion = {0.0, 0.0};
    private final int[] usosRuptura = {0, 0, 0};
    private final int[] usosReparacion = {0, 0};

    public ALNS_Strategy() { this(System.nanoTime()); }
    public ALNS_Strategy(long semilla) { this.random = new Random(semilla); }

    @Override
    public Solucion planificar(Dataset datos, Config_Simulacion config) {
        RouteFinder finder = new RouteFinder(datos);
        Map<String, List<Ruta>> candidatos = PlanificacionUtils.construirCandidatosRutas(datos, config, finder);

        Map<String, Ruta> propuestaNormal = construirInicialGreedy(datos, config, candidatos);
        Solucion solucionActual = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaNormal, datos, config);
        Map<String, Ruta> propuestaMejor = new HashMap<>(propuestaNormal);
        Solucion mejorSolucion = solucionActual;

        Map<String, Ruta> propuestaActual = new HashMap<>(propuestaNormal);

        // Optimización: evaluar cada 10 iteraciones, parar tras 5 sin mejora
        double temperatura = Math.max(1.0, solucionActual.getCostoTotal() * 0.05);
        int sinMejora = 0;
        final int MAX_SIN_MEJORA = 5;
        final int EVAL_CADA = 10;

        for (int iter = 1; iter <= Math.max(1, config.getIteracionesALNS()); iter++) {
            int opR = seleccionarPorRuleta(pesosRuptura);
            int opP = seleccionarPorRuleta(pesosReparacion);

            Map<String, Ruta> candidata = new HashMap<>(propuestaActual);
            Set<String> destruidos = aplicarRuptura(opR, candidata, datos, config, candidatos);
            aplicarReparacion(opP, candidata, new ArrayList<>(destruidos), datos, config, candidatos);
            intentarAsignarNoAsignados(candidata, datos, config, candidatos, 3);

            Solucion solCandidata = null;
            if (iter == 1 || iter % EVAL_CADA == 0) {
                solCandidata = PlanificacionUtils.evaluarAsignacion("ALNS", candidata, datos, config);
            }

            double recompensa = 0.0;
            if (solCandidata != null && solCandidata.getCostoTotal() < mejorSolucion.getCostoTotal()) {
                propuestaMejor = new HashMap<>(candidata);
                mejorSolucion = solCandidata;
                propuestaActual = candidata;
                solucionActual = solCandidata;
                recompensa = 6.0;
                sinMejora = 0;
            } else if (solCandidata != null && solCandidata.getCostoTotal() < solucionActual.getCostoTotal()) {
                propuestaActual = candidata;
                solucionActual = solCandidata;
                recompensa = 3.0;
                sinMejora = 0;
            } else if (solCandidata != null && debeAceptarPeorPorAnnealing(solCandidata, solucionActual, temperatura)) {
                propuestaActual = candidata;
                solucionActual = solCandidata;
                recompensa = 1.0;
            } else {
                sinMejora++;
            }

            if (sinMejora >= MAX_SIN_MEJORA) {
                break;
            }

            usosRuptura[opR]++;
            usosReparacion[opP]++;
            puntajesRuptura[opR] += recompensa;
            puntajesReparacion[opP] += recompensa;

            if (iter % Math.max(1, config.getVentanaActualizacionPesos()) == 0) {
                actualizarPesos(pesosRuptura, puntajesRuptura, usosRuptura, config.getTasaAprendizajePesos());
                actualizarPesos(pesosReparacion, puntajesReparacion, usosReparacion, config.getTasaAprendizajePesos());
            }

temperatura = Math.max(1e-6, temperatura * 0.995);
        }

        // Optimización: Solo fases críticas de reparación (forzarATiempo + eliminar rutas fuera plazo)
        propuestaMejor = forzarATiempo(propuestaMejor, datos, config, candidatos);
        propuestaMejor = eliminarRutasFueraDePlazo(propuestaMejor, datos, config);

        Solucion salida = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaMejor, datos, config);
        salida.setMetrica("pesoRupturaRandom", pesosRuptura[RUPTURA_RANDOM]);
        salida.setMetrica("pesoRupturaWorstDelay", pesosRuptura[RUPTURA_WORST_DELAY]);
        salida.setMetrica("pesoRupturaCongestion", pesosRuptura[RUPTURA_CONGESTION]);
        salida.setMetrica("pesoReparacionGreedy", pesosReparacion[REPARACION_GREEDY]);
        salida.setMetrica("pesoReparacionRegret", pesosReparacion[REPARACION_REGRET]);
        return salida;
    }

    // =====================================================================
    // CONSTRUCCIÓN INICIAL
    // =====================================================================

    private Map<String, Ruta> construirInicialGreedy(Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        Map<String, Ruta> propuesta = new HashMap<>();
        EstadoOperacional estado = new EstadoOperacional();
        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());
        paquetes.sort((a, b) -> {
            LocalDateTime deadlineA = PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .plus(PlanificacionUtils.getPlazoObjetivo(a, datos, config));
            LocalDateTime deadlineB = PlanificacionUtils.getCreacionUtc(b, datos, config)
                    .plus(PlanificacionUtils.getPlazoObjetivo(b, datos, config));
            return deadlineA.compareTo(deadlineB);
        });

        for (Paquete p : paquetes) {
            List<Ruta> rutas = candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST);
            if (rutas.isEmpty()) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);

            List<Ruta> rutasValidas = new ArrayList<>();
            List<Ruta> rutasFallback = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                } else {
                    rutasFallback.add(r);
                }
            }

            if (rutasValidas.isEmpty() && rutasFallback.isEmpty()) continue;

            List<Ruta> aEvaluar = rutasValidas.isEmpty() ? rutasFallback : rutasValidas;

            // Pre-filtrar: evaluar sin estado (rápido) y tomar las mejores 5
            aEvaluar.sort((r1, r2) -> Double.compare(
                    PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                    PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));
            int maxEval = Math.min(5, aEvaluar.size());

            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (int i = 0; i < maxEval; i++) {
                Ruta r = aEvaluar.get(i);
                if (!estado.puedeReservarRuta(p, r, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividualLight(p, r, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = r; }
            }
            if (mejor == null) continue;
            estado.reservarRutaSiFactible(p, mejor, creacion, datos, config);
            propuesta.put(p.getId(), mejor);
        }
        return propuesta;
    }

    // =====================================================================
    // RUPTURA
    // =====================================================================

    private Set<String> aplicarRuptura(int operador, Map<String, Ruta> propuesta,
                                       Dataset datos, Config_Simulacion config,
                                       Map<String, List<Ruta>> candidatos) {
        if (propuesta.isEmpty()) return Set.of();

        Set<String> destruidos = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>(propuesta.keySet());
        int cantidad = Math.max(2, (int) Math.ceil(ids.size() * config.getPorcentajeRuptura()));

        switch (operador) {
            case RUPTURA_RANDOM:
                Collections.shuffle(ids, random);
                for (int i = 0; i < Math.min(cantidad, ids.size()); i++) destruidos.add(ids.get(i));
                break;

            case RUPTURA_WORST_DELAY:
                ids.sort((a, b) -> Double.compare(scoreDeterioro(b, propuesta, datos, config),
                        scoreDeterioro(a, propuesta, datos, config)));
                for (int i = 0; i < Math.min(cantidad, ids.size()); i++) destruidos.add(ids.get(i));
                break;

            case RUPTURA_CONGESTION:
                destruidos = rupturaPorCongestion(propuesta, datos, config, cantidad);
                break;
        }

        for (String id : destruidos) propuesta.remove(id);
        return destruidos;
    }

    /**
     * Ruptura basada en matriz de congestión de aeropuertos.
     * Construye E[airport][hour] con ocupación actual y ataca los slots más llenos.
     */
    private Set<String> rupturaPorCongestion(Map<String, Ruta> propuesta,
                                             Dataset datos, Config_Simulacion config,
                                             int cantidad) {
        // Construir matriz de ocupación aeropuerto×hora
        Map<String, Map<LocalDateTime, Integer>> ocupacion = new HashMap<>();

        for (Map.Entry<String, Ruta> entry : propuesta.entrySet()) {
            Paquete p = datos.getPaquetePorId(entry.getKey());
            if (p == null) continue;
            Ruta r = entry.getValue();
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime instante = creacion;
            Aeropuerto apActual = datos.getAeropuerto(p.getOrigenOACI());
            if (apActual == null) apActual = datos.getAeropuerto(config.getAeropuertoHub());
            if (apActual == null) continue;

            for (Vuelo v : r.getVuelos()) {
                LocalDateTime hora = instante.truncatedTo(ChronoUnit.HOURS);
                while (hora.isBefore(v.getSalidaUtc())) {
                    ocupacion.computeIfAbsent(apActual.getCodigoOACI(), k -> new HashMap<>())
                            .merge(hora, p.getCantidad(), Integer::sum);
                    hora = hora.plusHours(1);
                }
                // Llegada al destino del vuelo
                instante = v.getLlegadaUtc();
                apActual = v.getDestino();
            }
            // Ocupación en aeropuerto destino final
            LocalDateTime horaInicio = instante.truncatedTo(ChronoUnit.HOURS);
            ocupacion.computeIfAbsent(apActual.getCodigoOACI(), k -> new HashMap<>())
                    .merge(horaInicio, p.getCantidad(), Integer::sum);
        }

        // Encontrar los slots más congestionados
        List<CeldaCongestion> celdas = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDateTime, Integer>> eAp : ocupacion.entrySet()) {
            String apCode = eAp.getKey();
            Aeropuerto ap = datos.getAeropuerto(apCode);
            int cap = ap != null ? ap.getCapacidadMaxima() : 400;
            for (Map.Entry<LocalDateTime, Integer> eSlot : eAp.getValue().entrySet()) {
                double ratio = (double) eSlot.getValue() / cap;
                celdas.add(new CeldaCongestion(apCode, eSlot.getKey(), ratio));
            }
        }
        celdas.sort((a, b) -> Double.compare(b.ratio, a.ratio));

        // Desasignar paquetes que usan los slots más congestionados
        Set<String> destruidos = new LinkedHashSet<>();
        for (CeldaCongestion celda : celdas) {
            if (destruidos.size() >= cantidad) break;
            for (Map.Entry<String, Ruta> entry : propuesta.entrySet()) {
                if (destruidos.size() >= cantidad) break;
                if (usaSlot(entry.getValue(), celda.aeropuerto, celda.hora, datos, config)) {
                    destruidos.add(entry.getKey());
                }
            }
        }
        return destruidos;
    }

    private boolean usaSlot(Ruta ruta, String apCode, LocalDateTime hora, Dataset datos, Config_Simulacion config) {
        // Verificar si la ruta pasa por este aeropuerto en esta hora
        for (Vuelo v : ruta.getVuelos()) {
            if (v.getOrigen().getCodigoOACI().equals(apCode)) {
                LocalDateTime h = hora;
                while (h.isBefore(v.getSalidaUtc())) {
                    if (h.equals(hora)) return true;
                    h = h.plusHours(1);
                }
            }
            if (v.getDestino().getCodigoOACI().equals(apCode)) {
                LocalDateTime llegadaHora = v.getLlegadaUtc().truncatedTo(ChronoUnit.HOURS);
                if (llegadaHora.equals(hora)) return true;
            }
        }
        return false;
    }

    private static class CeldaCongestion {
        final String aeropuerto;
        final LocalDateTime hora;
        final double ratio;
        CeldaCongestion(String a, LocalDateTime h, double r) {
            aeropuerto = a; hora = h; ratio = r;
        }
    }

    // =====================================================================
    // REPARACIÓN
    // =====================================================================

    private void aplicarReparacion(int operador, Map<String, Ruta> propuesta,
                                   List<String> ids, Dataset datos,
                                   Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
        List<String> trabajo = new ArrayList<>(ids);
        if (operador == REPARACION_REGRET) {
            reparacionRegret(propuesta, trabajo, datos, config, candidatos, estado);
        } else {
            reparacionGreedy(propuesta, trabajo, datos, config, candidatos, estado);
        }
    }

    private void reparacionGreedy(Map<String, Ruta> propuesta, List<String> ids,
                                  Dataset datos, Config_Simulacion config,
                                  Map<String, List<Ruta>> candidatos, EstadoOperacional estado) {
        Collections.shuffle(ids, random);
        for (String id : ids) {
            Paquete p = datos.getPaquetePorId(id);
            if (p == null) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);
            List<Ruta> rutas = candidatos.getOrDefault(id, EMPTY_RUTA_LIST);

            List<Ruta> rutasValidas = new ArrayList<>();
            List<Ruta> rutasFallback = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                } else {
                    rutasFallback.add(r);
                }
            }

            if (rutasValidas.isEmpty() && rutasFallback.isEmpty()) continue;

            List<Ruta> aEvaluar = rutasValidas.isEmpty() ? rutasFallback : rutasValidas;

            // Pre-filtrar: evaluar sin estado (rápido) y tomar las mejores 5
            aEvaluar.sort((r1, r2) -> Double.compare(
                    PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                    PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));
            int maxEval = Math.min(5, aEvaluar.size());

            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (int i = 0; i < maxEval; i++) {
                Ruta r = aEvaluar.get(i);
                if (!estado.puedeReservarRuta(p, r, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividualLight(p, r, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = r; }
            }
            if (mejor != null) {
                estado.reservarRutaSiFactible(p, mejor, creacion, datos, config);
                propuesta.put(id, mejor);
            }
        }
    }

    private void reparacionRegret(Map<String, Ruta> propuesta, List<String> ids,
                                  Dataset datos, Config_Simulacion config,
                                  Map<String, List<Ruta>> candidatos, EstadoOperacional estado) {
        Set<String> pendientes = new HashSet<>(ids);
        while (!pendientes.isEmpty()) {
            String mejorId = null;
            double mejorRegret = Double.NEGATIVE_INFINITY;
            Ruta mejorRutaParaMejorId = null;
            double mejorScoreParaMejorId = Double.POSITIVE_INFINITY;

            for (String id : pendientes) {
                Paquete p = datos.getPaquetePorId(id);
                if (p == null) continue;
                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
                Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
                LocalDateTime deadline = creacion.plus(plazo);
                List<Ruta> rutas = candidatos.getOrDefault(id, EMPTY_RUTA_LIST);

                List<Ruta> rutasValidas = new ArrayList<>();
                List<Ruta> rutasFallback = new ArrayList<>();
                for (Ruta r : rutas) {
                    if (!r.getLlegadaUtc().isAfter(deadline)) {
                        rutasValidas.add(r);
                    } else {
                        rutasFallback.add(r);
                    }
                }

                if (rutasValidas.isEmpty() && rutasFallback.isEmpty()) continue;

                List<Ruta> aEvaluar = rutasValidas.isEmpty() ? rutasFallback : rutasValidas;

                // Pre-filtrar: evaluar sin estado (rápido) y tomar las mejores 10
                aEvaluar.sort((r1, r2) -> Double.compare(
                        PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                        PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));
                int maxEval = Math.min(10, aEvaluar.size());

                double best = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
                Ruta bestRuta = null;
                for (int i = 0; i < maxEval; i++) {
                    Ruta r = aEvaluar.get(i);
                    if (!estado.puedeReservarRuta(p, r, creacion, datos, config)) continue;
                    double score = PlanificacionUtils.evaluarRutaIndividualLight(p, r, estado, datos, config);
                    if (score < best) { second = best; best = score; bestRuta = r; }
                    else if (score < second) { second = score; }
                }
                if (best == Double.POSITIVE_INFINITY) continue;
                if (second == Double.POSITIVE_INFINITY) second = best + 500.0;
                double regret = second - best;
                if (regret > mejorRegret) {
                    mejorRegret = regret;
                    mejorId = id;
                    mejorRutaParaMejorId = bestRuta;
                    mejorScoreParaMejorId = best;
                }
            }
            if (mejorId == null) break;

            Paquete p = datos.getPaquetePorId(mejorId);
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            estado.reservarRutaSiFactible(p, mejorRutaParaMejorId, creacion, datos, config);
            propuesta.put(mejorId, mejorRutaParaMejorId);
            pendientes.remove(mejorId);
        }
    }

    // =====================================================================
    // ASIGNAR NO ASIGNADOS
    // =====================================================================

    private void intentarAsignarNoAsignados(Map<String, Ruta> propuesta, Dataset datos,
                                            Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                                            int maxIntentos) {
        List<Paquete> sinRuta = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (!propuesta.containsKey(p.getId()) && !candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST).isEmpty()) {
                sinRuta.add(p);
            }
        }
        if (sinRuta.isEmpty()) return;

        sinRuta.sort((a, b) -> {
            int na = candidatos.getOrDefault(a.getId(), EMPTY_RUTA_LIST).size();
            int nb = candidatos.getOrDefault(b.getId(), EMPTY_RUTA_LIST).size();
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        int limite = Math.min(maxIntentos, sinRuta.size());
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);

        for (Paquete p : sinRuta.subList(0, limite)) {
            if (propuesta.containsKey(p.getId())) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);
            List<Ruta> rutas = candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST);

            List<Ruta> rutasValidas = new ArrayList<>();
            List<Ruta> rutasFallback = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                } else {
                    rutasFallback.add(r);
                }
            }

            if (rutasValidas.isEmpty() && rutasFallback.isEmpty()) continue;

            List<Ruta> aEvaluar = rutasValidas.isEmpty() ? rutasFallback : rutasValidas;

            // Pre-filtrar: evaluar sin estado (rápido) y tomar las mejores 10
            aEvaluar.sort((r1, r2) -> Double.compare(
                    PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                    PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));
            int maxEval = Math.min(10, aEvaluar.size());

            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (int i = 0; i < maxEval; i++) {
                Ruta r = aEvaluar.get(i);
                if (!estado.puedeReservarRuta(p, r, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividualLight(p, r, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = r; }
            }
            if (mejor != null) {
                estado.reservarRutaSiFactible(p, mejor, creacion, datos, config);
                propuesta.put(p.getId(), mejor);
            }
        }
    }

    // =====================================================================
    // UTILIDADES
    // =====================================================================

    private double scoreDeterioro(String paqueteId, Map<String, Ruta> propuesta, Dataset datos, Config_Simulacion config) {
        Ruta ruta = propuesta.get(paqueteId);
        if (ruta == null) return Double.MAX_VALUE;
        Paquete p = datos.getPaquetePorId(paqueteId);
        LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
        LocalDateTime limite = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));
        long tardanza = Math.max(0L, Duration.between(limite, ruta.getLlegadaUtc()).toMinutes());
        long duracion = Duration.between(creacion, ruta.getLlegadaUtc()).toMinutes();
        return tardanza * 50.0 + duracion;
    }

    private boolean debeAceptarPeorPorAnnealing(Solucion candidata, Solucion actual, double temperatura) {
        double delta = candidata.getCostoTotal() - actual.getCostoTotal();
        return random.nextDouble() < Math.exp(-delta / Math.max(1e-9, temperatura));
    }

    private int seleccionarPorRuleta(double[] pesos) {
        double suma = 0;
        for (double p : pesos) suma += p;
        double ticket = random.nextDouble() * suma;
        double acum = 0;
        for (int i = 0; i < pesos.length; i++) {
            acum += pesos[i];
            if (ticket <= acum) return i;
        }
        return pesos.length - 1;
    }

    private void actualizarPesos(double[] pesos, double[] puntajes, int[] usos, double tasa) {
        for (int i = 0; i < pesos.length; i++) {
            if (usos[i] > 0) {
                double promedio = puntajes[i] / usos[i];
                pesos[i] = (1.0 - tasa) * pesos[i] + tasa * Math.max(0.1, promedio);
            }
            puntajes[i] = 0.0;
            usos[i] = 0;
        }
    }

    // =====================================================================
    // FORZAR RUTAS A TIEMPO
    // =====================================================================

    private Map<String, Ruta> forzarATiempo(
            Map<String, Ruta> propuesta,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos
    ) {
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
        int mejorados = 0;

        List<Paquete> paquetesConProblema = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            Ruta r = propuesta.get(p.getId());
            if (r == null) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime deadline = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));
            if (r.getLlegadaUtc().isAfter(deadline)) {
                paquetesConProblema.add(p);
            }
        }
        paquetesConProblema.sort((a, b) -> {
            LocalDateTime deadlineA = PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .plus(PlanificacionUtils.getPlazoObjetivo(a, datos, config));
            LocalDateTime deadlineB = PlanificacionUtils.getCreacionUtc(b, datos, config)
                    .plus(PlanificacionUtils.getPlazoObjetivo(b, datos, config));
            return deadlineA.compareTo(deadlineB);
        });

        for (Paquete p : paquetesConProblema) {
            Ruta r = propuesta.get(p.getId());
            if (r == null) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime deadline = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));

            if (!r.getLlegadaUtc().isAfter(deadline)) continue;

            List<Ruta> alternativas = candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST);
            for (Ruta alt : alternativas) {
                if (alt.getLlegadaUtc().isAfter(deadline)) continue;
                if (estado.puedeReservarRuta(p, alt, creacion, datos, config)) {
                    estado.reservarRutaSiFactible(p, alt, creacion, datos, config);
                    propuesta.put(p.getId(), alt);
                    mejorados++;
                    break;
                }
            }
        }

        if (mejorados > 0) {
            System.out.println("  [ALNS] forzarATiempo: " + mejorados + " paquetes reasignados");
        }
        return propuesta;
    }

    // =====================================================================
    // ELIMINAR RUTAS FUERA DEL DEADLINE - FILTRO FINAL ESTRICTO
    // =====================================================================

    private Map<String, Ruta> eliminarRutasFueraDePlazo(
            Map<String, Ruta> propuesta,
            Dataset datos,
            Config_Simulacion config
    ) {
        Map<String, Ruta> resultado = new HashMap<>();

        for (Paquete p : datos.getPaquetes()) {
            Ruta r = propuesta.get(p.getId());
            if (r == null) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime deadline = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));

            // Mantener ruta aunque esté fuera de plazo: mejor tarde que nunca
            resultado.put(p.getId(), r);
        }

        return resultado;
    }
}
