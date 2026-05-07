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

        // Intento 1: Enfoque reverso - asignar todo y remover el mínimo para factibilidad
        Map<String, Ruta> propuestaReversa = construirReverso(datos, config, candidatos);
        Solucion solReversa = PlanificacionUtils.evaluarAsignacion("ALNS-reverso", propuestaReversa, datos, config);

        // Intento 2: Enfoque ALNS normal
        Map<String, Ruta> propuestaNormal = construirInicialGreedy(datos, config, candidatos);
        Solucion solucionActual = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaNormal, datos, config);
        Map<String, Ruta> propuestaMejor = new HashMap<>(propuestaNormal);
        Solucion mejorSolucion = solucionActual;

        Map<String, Ruta> propuestaActual = new HashMap<>(propuestaNormal);

        // Tomar el mejor de los dos intentos iniciales
        if (solReversa.getCostoTotal() < mejorSolucion.getCostoTotal()) {
            propuestaMejor = new HashMap<>(propuestaReversa);
            mejorSolucion = solReversa;
            propuestaActual = propuestaReversa;
            solucionActual = solReversa;
        }

double temperatura = Math.max(1.0, solucionActual.getCostoTotal() * 0.05);
        int sinMejora = 0;
        final int MAX_SIN_MEJORA = 5;
        final int EVAL_CADA = 3;

        for (int iter = 1; iter <= Math.max(1, config.getIteracionesALNS()); iter++) {
            int opR = seleccionarPorRuleta(pesosRuptura);
            int opP = seleccionarPorRuleta(pesosReparacion);

            Map<String, Ruta> candidata = new HashMap<>(propuestaActual);
            Set<String> destruidos = aplicarRuptura(opR, candidata, datos, config, candidatos);
            aplicarReparacion(opP, candidata, new ArrayList<>(destruidos), datos, config, candidatos);
            intentarAsignarNoAsignados(candidata, datos, config, candidatos, 5);

            Solucion solCandidata = null;
            if (iter % EVAL_CADA == 0) {
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

        // Reparación final agresiva: multi-paso
        propuestaMejor = repararSinRutas(propuestaMejor, datos, config, candidatos);
        // Segundo paso: intentar remover y reasignar para liberar espacio
        propuestaMejor = repararRemoviendo(propuestaMejor, datos, config, candidatos);

        // Paso 3: forzar rutas a tiempo si existen alternativas
        propuestaMejor = forzarATiempo(propuestaMejor, datos, config, candidatos);

        // Paso 4: ELIMINAR rutas fuera del deadline - STRICTO
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
            // PRIORIZAR por deadline más cercano (más urgente)
            LocalDateTime deadlineA = PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .plus(PlanificacionUtils.getPlazoObjetivo(a, datos, config));
            LocalDateTime deadlineB = PlanificacionUtils.getCreacionUtc(b, datos, config)
                    .plus(PlanificacionUtils.getPlazoObjetivo(b, datos, config));
            return deadlineA.compareTo(deadlineB);
        });

        for (Paquete p : paquetes) {
            List<Ruta> rutas = candidatos.getOrDefault(p.getId(), List.of());
            if (rutas.isEmpty()) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);

            // FILTRAR: solo rutas dentro del deadline
            List<Ruta> rutasValidas = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                }
            }

            if (rutasValidas.isEmpty()) continue;

            // PRIMERO: intentar asignar rutas DENTRO del deadline
            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (Ruta r : rutasValidas) {
                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(p, r, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividual(p, r, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = r; }
            }
            // Si no hay ruta dentro del deadline, NO asignar nada (esperar a reparación)
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
            LocalDateTime horaFin = instante.truncatedTo(ChronoUnit.HOURS).plusHours(1);
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
                celdas.add(new CeldaCongestion(apCode, eSlot.getKey(), eSlot.getValue(), ratio));
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
        final int ocupacion;
        final double ratio;
        CeldaCongestion(String a, LocalDateTime h, int o, double r) {
            aeropuerto = a; hora = h; ocupacion = o; ratio = r;
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
            List<Ruta> rutas = candidatos.getOrDefault(id, List.of());

            // FILTRAR: solo rutas dentro del deadline
            List<Ruta> rutasValidas = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                }
            }

            if (rutasValidas.isEmpty()) continue;

            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (Ruta r : rutasValidas) {
                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(p, r, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividual(p, r, estado, datos, config);
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
            Ruta mejorRuta = null;
            double mejorRegret = Double.NEGATIVE_INFINITY;

            for (String id : pendientes) {
                Paquete p = datos.getPaquetePorId(id);
                if (p == null) continue;
                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
                Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
                LocalDateTime deadline = creacion.plus(plazo);
                List<Ruta> rutas = candidatos.getOrDefault(id, List.of());

// FILTRAR: solo rutas dentro del deadline
            List<Ruta> rutasValidas = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                }
            }

                if (rutasValidas.isEmpty()) continue;

                double best = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
                for (Ruta r : rutasValidas) {
                    EstadoOperacional prueba = estado.copia();
                    if (!prueba.reservarRutaSiFactible(p, r, creacion, datos, config)) continue;
                    double score = PlanificacionUtils.evaluarRutaIndividual(p, r, estado, datos, config);
                    if (score < best) { second = best; best = score; mejorRuta = r; }
                    else if (score < second) { second = score; }
                }
                if (best == Double.POSITIVE_INFINITY) continue;
                if (second == Double.POSITIVE_INFINITY) second = best + 500.0;
                double regret = second - best;
                if (regret > mejorRegret) { mejorRegret = regret; mejorId = id; }
            }
            if (mejorId == null) break;

            Paquete p = datos.getPaquetePorId(mejorId);
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);
            List<Ruta> rutas = candidatos.getOrDefault(mejorId, List.of());

            // FILTRAR: STRICTO solo rutas dentro del deadline
            List<Ruta> rutasValidas = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                }
            }

            if (rutasValidas.isEmpty()) continue;

            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (Ruta r : rutasValidas) {
                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(p, r, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividual(p, r, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = r; }
            }
            if (mejor != null) {
                estado.reservarRutaSiFactible(p, mejor, creacion, datos, config);
                propuesta.put(mejorId, mejor);
            }
            pendientes.remove(mejorId);
        }
    }

    // =====================================================================
    // ENFOQUE REVERSO: asignar todo, luego remover el mínimo
    // =====================================================================

    private Map<String, Ruta> construirReverso(Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        Map<String, Ruta> propuesta = new HashMap<>();
        for (Paquete p : datos.getPaquetes()) {
            List<Ruta> rutas = candidatos.getOrDefault(p.getId(), List.of());
            if (rutas.isEmpty()) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime deadline = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));

            // FILTRAR: solo rutas dentro del deadline
            List<Ruta> rutasValidas = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                }
            }
            if (rutasValidas.isEmpty()) continue;

            // Elegir ruta con menos escalas
            Ruta mejor = rutasValidas.stream()
                    .min(Comparator.comparingInt(Ruta::getCantidadSaltos)
                            .thenComparing(Ruta::getLlegadaUtc))
                    .orElse(null);
            if (mejor != null) propuesta.put(p.getId(), mejor);
        }

        // Paso 2: Remover iterativamente paquetes hasta lograr factibilidad
        EstadoOperacional estado = new EstadoOperacional();
        Set<String> removidos = new HashSet<>();
        List<String> orden = new ArrayList<>(propuesta.keySet());
        // Ordenar: paquetes con rutas más costosas primero (más propensos a causar colapso)
        orden.sort((a, b) -> {
            Paquete pa = datos.getPaquetePorId(a);
            Paquete pb = datos.getPaquetePorId(b);
            Ruta ra = propuesta.get(a);
            Ruta rb = propuesta.get(b);
            double ca = ra == null ? 0 : PlanificacionUtils.evaluarRutaIndividual(pa, ra, datos, config);
            double cb = rb == null ? 0 : PlanificacionUtils.evaluarRutaIndividual(pb, rb, datos, config);
            return Double.compare(cb, ca); // más costoso primero
        });

        // Intentar reservar; si falla, remover
        for (String id : orden) {
            Paquete p = datos.getPaquetePorId(id);
            Ruta r = propuesta.get(id);
            if (p == null || r == null) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            if (!estado.reservarRutaSiFactible(p, r, creacion, datos, config)) {
                propuesta.remove(id);
                removidos.add(id);
            }
}

        // System.out.println("  [ALNS] forzarATiempo: " + mejorados + " paquetes reasignados");
        return propuesta;
    }

    // =====================================================================
    // REPARACIÓN AGRESIVA: remover + reasignar para liberar espacio
    // =====================================================================

    private Map<String, Ruta> repararRemoviendo(Map<String, Ruta> propuestaBase,
                                                Dataset datos, Config_Simulacion config,
                                                Map<String, List<Ruta>> candidatos) {
        Map<String, Ruta> propuesta = new HashMap<>(propuestaBase);

        // Identificar paquetes sin asignar con candidatos
        List<Paquete> sinAsignar = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (!propuesta.containsKey(p.getId())) {
                List<Ruta> rutas = candidatos.getOrDefault(p.getId(), List.of());
                if (!rutas.isEmpty()) sinAsignar.add(p);
            }
        }
        if (sinAsignar.isEmpty()) return propuesta;

        sinAsignar.sort((a, b) ->
                Integer.compare(candidatos.getOrDefault(a.getId(), List.of()).size(),
                        candidatos.getOrDefault(b.getId(), List.of()).size()));

        int mejorados = 0;
        int maxIntentos = Math.min(sinAsignar.size() * 3, 50); // Limitar para no ser tan lento

        for (Paquete pendiente : sinAsignar) {
            if (maxIntentos-- <= 0) break;
            if (propuesta.containsKey(pendiente.getId())) continue;

            LocalDateTime creacionP = PlanificacionUtils.getCreacionUtc(pendiente, datos, config);
            LocalDateTime deadlineP = creacionP.plus(PlanificacionUtils.getPlazoObjetivo(pendiente, datos, config));
            List<Ruta> rutasP = candidatos.getOrDefault(pendiente.getId(), List.of());

            // FILTRAR: solo rutas dentro del deadline
            List<Ruta> rutasValidasP = new ArrayList<>();
            for (Ruta r : rutasP) {
                if (!r.getLlegadaUtc().isAfter(deadlineP)) {
                    rutasValidasP.add(r);
                }
            }
            if (rutasValidasP.isEmpty()) continue;

            // Intento directo
            boolean encontrado = false;
            EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
            for (Ruta r : rutasValidasP) {
                EstadoOperacional prueba = estado.copia();
                if (prueba.reservarRutaSiFactible(pendiente, r, creacionP, datos, config)) {
                    estado.reservarRutaSiFactible(pendiente, r, creacionP, datos, config);
                    propuesta.put(pendiente.getId(), r);
                    encontrado = true;
                    mejorados++;
                    break;
                }
            }
            if (encontrado) continue;

            // Identificar aeropuertos más congestionados de las rutas candidatas del pendiente
            // y remover paquetes que usen esos aeropuertos
            Set<String> aeropuertosCriticos = new HashSet<>();
            for (Ruta r : rutasP.subList(0, Math.min(3, rutasP.size()))) {
                for (Vuelo v : r.getVuelos()) {
                    aeropuertosCriticos.add(v.getOrigen().getCodigoOACI());
                    aeropuertosCriticos.add(v.getDestino().getCodigoOACI());
                }
            }

            // Remover paquetes que usan aeropuertos críticos, ordenados por costo descendente
            List<String> candidatosRemover = new ArrayList<>();
            for (Map.Entry<String, Ruta> entry : propuesta.entrySet()) {
                Ruta r = entry.getValue();
                for (Vuelo v : r.getVuelos()) {
                    if (aeropuertosCriticos.contains(v.getOrigen().getCodigoOACI()) ||
                        aeropuertosCriticos.contains(v.getDestino().getCodigoOACI())) {
                        candidatosRemover.add(entry.getKey());
                        break;
                    }
                }
            }

            // Ordenar por mayor consumo de capacidad (más escalas × cantidad)
            candidatosRemover.sort((a, b) -> {
                Ruta ra = propuesta.get(a);
                Ruta rb = propuesta.get(b);
                int ca = ra != null ? ra.getCantidadSaltos() : 0;
                int cb = rb != null ? rb.getCantidadSaltos() : 0;
                return Integer.compare(cb, ca);
            });

            // Intentar remover los top candidatos
            int maxRemover = Math.min(5, candidatosRemover.size());
            for (int i = 0; i < maxRemover; i++) {
                String idRemover = candidatosRemover.get(i);
                Paquete pRemover = datos.getPaquetePorId(idRemover);
                Ruta rRemover = propuesta.get(idRemover);
                if (pRemover == null || rRemover == null) continue;

                propuesta.remove(idRemover);
                estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);

                for (Ruta r : rutasP) {
                    EstadoOperacional prueba = estado.copia();
                    if (prueba.reservarRutaSiFactible(pendiente, r, creacionP, datos, config)) {
                        estado.reservarRutaSiFactible(pendiente, r, creacionP, datos, config);
                        propuesta.put(pendiente.getId(), r);
                        mejorados++;
                        encontrado = true;
                        break;
                    }
                }
                if (encontrado) break;
                propuesta.put(idRemover, rRemover); // Restaurar
            }
        }

        // if (mejorados > 0) {
        //     System.out.println(String.format(Locale.ROOT,
        //             "  [ALNS] repararRemoviendo: %d paquetes reasignados", mejorados));
        // }
        return propuesta;
    }

    // =====================================================================
    // ASIGNAR NO ASIGNADOS
    // =====================================================================

    private void intentarAsignarNoAsignados(Map<String, Ruta> propuesta, Dataset datos,
                                            Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                                            int maxIntentos) {
        List<Paquete> sinRuta = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (!propuesta.containsKey(p.getId()) && !candidatos.getOrDefault(p.getId(), List.of()).isEmpty()) {
                sinRuta.add(p);
            }
        }
        if (sinRuta.isEmpty()) return;

        sinRuta.sort((a, b) -> {
            int na = candidatos.getOrDefault(a.getId(), List.of()).size();
            int nb = candidatos.getOrDefault(b.getId(), List.of()).size();
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        int limite = Math.min(maxIntentos, sinRuta.size());
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);

        for (Paquete p : sinRuta.subList(0, limite)) {
            if (propuesta.containsKey(p.getId())) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            List<Ruta> rutas = candidatos.getOrDefault(p.getId(), List.of());
            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (Ruta r : rutas) {
                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(p, r, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividual(p, r, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = r; }
            }
            if (mejor != null) {
                estado.reservarRutaSiFactible(p, mejor, creacion, datos, config);
                propuesta.put(p.getId(), mejor);
            }
        }
    }

    // =====================================================================
    // REPARACIÓN FINAL
    // =====================================================================

    private Map<String, Ruta> repararSinRutas(Map<String, Ruta> propuestaBase,
                                              Dataset datos, Config_Simulacion config,
                                              Map<String, List<Ruta>> candidatos) {
        Map<String, Ruta> propuesta = new HashMap<>(propuestaBase);
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
        List<Paquete> sinRuta = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (!propuesta.containsKey(p.getId())) sinRuta.add(p);
        }
        sinRuta.sort((a, b) -> {
            int na = candidatos.getOrDefault(a.getId(), List.of()).size();
            int nb = candidatos.getOrDefault(b.getId(), List.of()).size();
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        int totalSinRuta = sinRuta.size();
        int conCandidatos = 0;
        int asignadosExtra = 0;
        int bloqueadoVuelo = 0;
        int bloqueadoAeropuerto = 0;

for (Paquete p : sinRuta) {
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);
            List<Ruta> rutas = candidatos.getOrDefault(p.getId(), List.of());

            // FILTRAR: solo rutas dentro del deadline
            List<Ruta> rutasValidas = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) {
                    rutasValidas.add(r);
                }
            }

            if (rutasValidas.isEmpty()) continue;
            conCandidatos++;

            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (Ruta r : rutasValidas) {
                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(p, r, creacion, datos, config)) {
                    boolean fallaVuelo = false;
                    for (Vuelo v : r.getVuelos()) {
                        if (estado.getCargaVuelo(v.getId()) + p.getCantidad() > v.getCapacidadCarga()) {
                            fallaVuelo = true; break;
                        }
                    }
                    if (fallaVuelo) bloqueadoVuelo++;
                    else bloqueadoAeropuerto++;
                    continue;
                }
                double score = PlanificacionUtils.evaluarRutaIndividual(p, r, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = r; }
            }
            if (mejor != null) {
                estado.reservarRutaSiFactible(p, mejor, creacion, datos, config);
                propuesta.put(p.getId(), mejor);
                asignadosExtra++;
            }
        }

        // System.out.println(String.format(Locale.ROOT,
        //         "  [ALNS] reparacionSinRutas: totalSinRuta=%d, conCandidatos=%d, asignadosExtra=%d | bloqueos: vuelo=%d aeropuerto=%d",
        //         totalSinRuta, conCandidatos, asignadosExtra, bloqueadoVuelo, bloqueadoAeropuerto));
        return propuesta;
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

        // PRIORIZAR paquetes por deadline más cercano
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

            List<Ruta> alternativas = candidatos.getOrDefault(p.getId(), List.of());
            for (Ruta alt : alternativas) {
                if (alt.getLlegadaUtc().isAfter(deadline)) continue;
                EstadoOperacional prueba = estado.copia();
                if (prueba.reservarRutaSiFactible(p, alt, creacion, datos, config)) {
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
        int eliminados = 0;
        Map<String, Ruta> resultado = new HashMap<>();

        for (Paquete p : datos.getPaquetes()) {
            Ruta r = propuesta.get(p.getId());
            if (r == null) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime deadline = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));

            // STRICTO: solo mantener rutas que lleguen ANTES del deadline
            if (!r.getLlegadaUtc().isAfter(deadline)) {
                resultado.put(p.getId(), r);
            } else {
                eliminados++;
            }
        }

        // System.out.println("  [ALNS] eliminarFueraPlazo: " + eliminados + " rutas eliminadas por llegar tarde");
        return resultado;
    }
}
