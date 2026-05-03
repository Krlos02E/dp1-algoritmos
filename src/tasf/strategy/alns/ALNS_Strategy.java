package tasf.strategy.alns;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.core.Solucion;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;
import tasf.strategy.PlanificadorStrategy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class ALNS_Strategy implements PlanificadorStrategy {
    private static final int RUPTURA_RANDOM = 0;
    private static final int RUPTURA_WORST_DELAY = 1;
    private static final int REPARACION_GREEDY = 0;
    private static final int REPARACION_REGRET = 1;

    private final Random random;
    private final double[] pesosRuptura = {1.0, 1.0};
    private final double[] pesosReparacion = {1.0, 1.0};
    private final double[] puntajesRuptura = {0.0, 0.0};
    private final double[] puntajesReparacion = {0.0, 0.0};
    private final int[] usosRuptura = {0, 0};
    private final int[] usosReparacion = {0, 0};

    public ALNS_Strategy() {
        this(System.nanoTime());
    }

    public ALNS_Strategy(long semilla) {
        this.random = new Random(semilla);
    }

    @Override
    public Solucion planificar(Dataset datos, Config_Simulacion config) {
        RouteFinder finder = new RouteFinder(datos);
        Map<String, List<Ruta>> candidatos = PlanificacionUtils.construirCandidatosRutas(datos, config, finder);

        // Diagnostico de candidatos
        int totalPaquetes = datos.getPaquetes().size();
        int conCandidatos = 0;
        int sinCandidatos = 0;
        int totalRutas = 0;
        for (Paquete p : datos.getPaquetes()) {
            List<Ruta> c = candidatos.getOrDefault(p.getId(), List.of());
            if (c.isEmpty()) sinCandidatos++;
            else { conCandidatos++; totalRutas += c.size(); }
        }
        System.out.printf("[ALNS] candidatos: paquetes=%d, conRutas=%d, sinRutas=%d, avgRutas=%.1f%n",
                totalPaquetes, conCandidatos, sinCandidatos, conCandidatos > 0 ? (double) totalRutas / conCandidatos : 0);

        Map<String, Ruta> propuestaActual = construirInicialGreedy(datos, config, candidatos);
        Solucion solucionActual = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaActual, datos, config);

        // Diagnostico post-greedy
        int asignadosGreedy = propuestaActual.size();
        System.out.printf("[ALNS] greedy: asignados=%d/%d (%.1f%%)%n",
                asignadosGreedy, totalPaquetes, 100.0 * asignadosGreedy / totalPaquetes);

        Map<String, Ruta> propuestaMejor = new HashMap<>(propuestaActual);
        Solucion mejorSolucion = solucionActual;

        double temperatura = Math.max(1.0, solucionActual.getCostoTotal() * 0.05);

        // Bucle ALNS: selecciona operadores por ruleta, destruye/recupera solución y decide aceptación.
        for (int iter = 1; iter <= Math.max(1, config.getIteracionesALNS()); iter++) {
            int operadorRuptura = seleccionarPorRuleta(pesosRuptura);
            int operadorReparacion = seleccionarPorRuleta(pesosReparacion);

            RupturaResultado ruptura = aplicarRuptura(operadorRuptura, propuestaActual, solucionActual, datos, config);

            Map<String, Ruta> propuestaCandidata = new HashMap<>(propuestaActual);
            // Se retira la asignacion de ruta de varios paquetes para reinsertarlos luego.
            for (String paqueteImpactadoId : ruptura.getPaquetesImpactados()) {
                propuestaCandidata.remove(paqueteImpactadoId);
            }

            aplicarReparacion(operadorReparacion, propuestaCandidata, ruptura.getPaquetesImpactados(), datos, config, candidatos, ruptura.getVuelosBloqueados());

            Solucion solucionCandidata = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaCandidata, datos, config);

            double recompensa = 0.0;
            if (solucionCandidata.getCostoTotal() < mejorSolucion.getCostoTotal()) {
                propuestaMejor = new HashMap<>(propuestaCandidata);
                mejorSolucion = solucionCandidata;
                propuestaActual = propuestaCandidata;
                solucionActual = solucionCandidata;
                recompensa = 6.0;
            } else if (solucionCandidata.getCostoTotal() < solucionActual.getCostoTotal()) {
                propuestaActual = propuestaCandidata;
                solucionActual = solucionCandidata;
                recompensa = 3.0;
            } else {
                if (debeAceptarPeorPorAnnealing(solucionCandidata, solucionActual, temperatura)) {
                    propuestaActual = propuestaCandidata;
                    solucionActual = solucionCandidata;
                    recompensa = 1.0;
                }
            }

            usosRuptura[operadorRuptura]++;
            usosReparacion[operadorReparacion]++;
            puntajesRuptura[operadorRuptura] += recompensa;
            puntajesReparacion[operadorReparacion] += recompensa;

            // Aprendizaje adaptativo: recalibra pesos con promedio de recompensas por operador.
            if (iter % Math.max(1, config.getVentanaActualizacionPesos()) == 0) {
                actualizarPesos(pesosRuptura, puntajesRuptura, usosRuptura, config.getTasaAprendizajePesos());
                actualizarPesos(pesosReparacion, puntajesReparacion, usosReparacion, config.getTasaAprendizajePesos());
            }

            temperatura = Math.max(1e-6, temperatura * 0.995);
        }

        System.out.printf("[ALNS] post-loop: mejor=%d asignados, costo=%.0f%n",
                propuestaMejor.size(), mejorSolucion.getCostoTotal());

        // Fase de recuperación: intentar asignar TODOS los paquetes sin ruta
        propuestaMejor = repararSinRutas(propuestaMejor, datos, config, candidatos);

        Solucion salida = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaMejor, datos, config);
        salida.setMetrica("pesoRupturaRandom", pesosRuptura[RUPTURA_RANDOM]);
        salida.setMetrica("pesoRupturaWorstDelay", pesosRuptura[RUPTURA_WORST_DELAY]);
        salida.setMetrica("pesoReparacionGreedy", pesosReparacion[REPARACION_GREEDY]);
        salida.setMetrica("pesoReparacionRegret", pesosReparacion[REPARACION_REGRET]);
        return salida;
    }

    /** Re-balanceo: redistribuye paquetes para maximizar asignaciones totales.
     *  Para cada paquete sin ruta, intenta encontrar una ruta factible.
     *  Si no existe, intenta mover un paquete asignado a otra ruta para liberar capacidad. */
    private Map<String, Ruta> construirInicialGreedy(Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        Map<String, Ruta> propuesta = new HashMap<>();
        EstadoOperacional estado = new EstadoOperacional();
        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());

        // Ordenar por numero de rutas candidatas (asc), luego por creacion
        paquetes.sort((a, b) -> {
            int na = candidatos.getOrDefault(a.getId(), List.of()).size();
            int nb = candidatos.getOrDefault(b.getId(), List.of()).size();
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        for (Paquete paquete : paquetes) {
            List<Ruta> rutas = candidatos.getOrDefault(paquete.getId(), List.of());
            if (rutas.isEmpty()) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);

            // Primero: encontrar todas las rutas factibles
            List<Ruta> factibles = new ArrayList<>();
            for (Ruta ruta : rutas) {
                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(paquete, ruta, creacion, datos, config)) continue;
                factibles.add(ruta);
            }
            if (factibles.isEmpty()) continue;

            // Elegir la ruta con MAXIMA capacidad residual (minima congestion)
            Ruta seleccionada = null;
            double maxResidual = Double.NEGATIVE_INFINITY;
            for (Ruta ruta : factibles) {
                double minResidual = Double.POSITIVE_INFINITY;
                for (Vuelo v : ruta.getVuelos()) {
                    int carga = estado.getCargaVuelo(v.getId());
                    int cap = v.getCapacidadCarga();
                    double residual = (double) (cap - carga) / Math.max(1, cap);
                    if (residual < minResidual) minResidual = residual;
                }
                // Tambien considerar plazo
                LocalDateTime limite = creacion.plus(PlanificacionUtils.getPlazoObjetivo(paquete, datos, config));
                long retrasoMin = Math.max(0, Duration.between(limite, ruta.getLlegadaUtc()).toMinutes());
                double scoreResidual = minResidual * 1000.0 - retrasoMin;

                if (scoreResidual > maxResidual) {
                    maxResidual = scoreResidual;
                    seleccionada = ruta;
                }
            }

            if (seleccionada != null) {
                estado.reservarRutaSiFactible(paquete, seleccionada, creacion, datos, config);
                propuesta.put(paquete.getId(), seleccionada);
            }
        }

        return propuesta;
    }

    private RupturaResultado aplicarRuptura(int operador, Map<String, Ruta> propuestaActual, Solucion solucionActual, Dataset datos, Config_Simulacion config) {
        if (propuestaActual.isEmpty()) {
            return new RupturaResultado(List.of(), Set.of());
        }

        List<String> paquetesOrdenados = new ArrayList<>(propuestaActual.keySet());
        int cantidad = Math.max(2, (int) Math.ceil(paquetesOrdenados.size() * config.getPorcentajeRuptura()));

        List<String> ordenReinsercion;
        if (operador == RUPTURA_WORST_DELAY) {
            ordenReinsercion = rupturaWorstDelay(propuestaActual, solucionActual, datos, config, cantidad);
        } else {
            ordenReinsercion = rupturaRandom(paquetesOrdenados, cantidad);
        }

        return new RupturaResultado(ordenReinsercion, Set.of());
    }

    private List<String> rupturaRandom(List<String> paquetes, int cantidad) {
        List<String> seleccionados = new ArrayList<>(paquetes);
        Collections.shuffle(seleccionados, random);
        return new ArrayList<>(seleccionados.subList(0, Math.min(cantidad, seleccionados.size())));
    }

    private List<String> rupturaWorstDelay(Map<String, Ruta> propuestaActual, Solucion solucionActual, Dataset datos, Config_Simulacion config, int cantidad) {
        List<String> paquetes = new ArrayList<>(propuestaActual.keySet());
        paquetes.sort((a, b) -> Double.compare(
                scoreDeterioro(b, propuestaActual, solucionActual, datos, config),
                scoreDeterioro(a, propuestaActual, solucionActual, datos, config)
        ));

        List<String> seleccionados = new ArrayList<>();
        for (String paqueteId : paquetes) {
            if (seleccionados.size() >= cantidad) {
                break;
            }
            seleccionados.add(paqueteId);
        }
        return seleccionados;
    }

    private double scoreDeterioro(String paqueteId, Map<String, Ruta> propuestaActual, Solucion solucionActual, Dataset datos, Config_Simulacion config) {
        Ruta ruta = propuestaActual.get(paqueteId);
        if (ruta == null) {
            return Double.MAX_VALUE;
        }

        Paquete paquete = datos.getPaquetePorId(paqueteId);
        LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
        LocalDateTime limite = creacion.plus(PlanificacionUtils.getPlazoObjetivo(paquete, datos, config));
        long tardanza = Math.max(0L, Duration.between(limite, ruta.getLlegadaUtc()).toMinutes());
        long duracion = Duration.between(creacion, ruta.getLlegadaUtc()).toMinutes();
        double noAsignadoBonus = solucionActual.getPaquetesNoAsignados().contains(paqueteId) ? 1_000_000.0 : 0.0;
        return noAsignadoBonus + tardanza * 50.0 + duracion;
    }

    private void aplicarReparacion(int operador, Map<String, Ruta> propuesta, List<String> paquetesImpactados, Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos, Set<String> vuelosBloqueados) {
        if (operador == REPARACION_REGRET) {
            reparacionRegret(propuesta, paquetesImpactados, datos, config, candidatos, vuelosBloqueados);
            return;
        }
        reparacionGreedy(propuesta, paquetesImpactados, datos, config, candidatos, vuelosBloqueados);

        // Adicional: intentar asignar paquetes sin ruta (muestreado) que se beneficien de la capacidad liberada
        intentarAsignarSinRuta(propuesta, datos, config, candidatos, vuelosBloqueados, Math.min(20, datos.getPaquetes().size() / 100));
    }

    private void intentarAsignarSinRuta(Map<String, Ruta> propuesta, Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos, Set<String> vuelosBloqueados, int maxIntentos) {
        // Recolectar paquetes sin ruta
        List<Paquete> sinRuta = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (!propuesta.containsKey(p.getId()) && !candidatos.getOrDefault(p.getId(), List.of()).isEmpty()) {
                sinRuta.add(p);
            }
        }
        if (sinRuta.isEmpty()) return;

        // Muestrear: tomar subconjunto aleatorio ordenado por restricción
        sinRuta.sort((a, b) -> {
            int na = candidatos.getOrDefault(a.getId(), List.of()).size();
            int nb = candidatos.getOrDefault(b.getId(), List.of()).size();
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        int limite = Math.min(maxIntentos, sinRuta.size());
        // Tomar los primeros 'limite' (mas restringidos)
        List<Paquete> muestra = sinRuta.subList(0, limite);

        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);

        for (Paquete paquete : muestra) {
            if (propuesta.containsKey(paquete.getId())) continue;
            List<Ruta> rutas = candidatos.getOrDefault(paquete.getId(), List.of());
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;

            for (Ruta ruta : rutas) {
                boolean bloqueada = false;
                if (vuelosBloqueados != null && !vuelosBloqueados.isEmpty()) {
                    for (Vuelo v : ruta.getVuelos()) {
                        if (vuelosBloqueados.contains(v.getId())) { bloqueada = true; break; }
                    }
                }
                if (bloqueada) continue;

                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(paquete, ruta, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividual(paquete, ruta, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = ruta; }
            }
            if (mejor != null) {
                estado.reservarRutaSiFactible(paquete, mejor, creacion, datos, config);
                propuesta.put(paquete.getId(), mejor);
            }
        }
    }

    private void reparacionGreedy(Map<String, Ruta> propuesta, List<String> paquetesImpactados, Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos, Set<String> vuelosBloqueados) {
        List<String> ids = new ArrayList<>(paquetesImpactados);
        Collections.shuffle(ids, random);

        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
        for (String id : ids) {
            Paquete paquete = datos.getPaquetePorId(id);
            List<RutaScore> factibles = evaluarRutasFactibles(paquete, candidatos.getOrDefault(id, List.of()), estado, propuesta, datos, config, vuelosBloqueados);
            if (!factibles.isEmpty()) {
                Ruta elegida = factibles.get(0).ruta;
                estado.reservarRutaSiFactible(
                        paquete,
                        elegida,
                        PlanificacionUtils.getCreacionUtc(paquete, datos, config),
                        datos,
                        config
                );
                propuesta.put(id, elegida);
            }
        }
    }

    private void reparacionRegret(Map<String, Ruta> propuesta, List<String> paquetesImpactados, Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos, Set<String> vuelosBloqueados) {
        Set<String> pendientes = new HashSet<>(paquetesImpactados);
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);

        while (!pendientes.isEmpty()) {
            String mejorPaquete = null;
            Ruta mejorRuta = null;
            double mejorRegret = Double.NEGATIVE_INFINITY;

            for (String id : pendientes) {
                Paquete paquete = datos.getPaquetePorId(id);
                List<RutaScore> factibles = evaluarRutasFactibles(paquete, candidatos.getOrDefault(id, List.of()), estado, propuesta, datos, config, vuelosBloqueados);
                if (factibles.isEmpty()) {
                    continue;
                }

                double best = factibles.get(0).score;
                double second = factibles.size() > 1 ? factibles.get(1).score : best + 500.0;
                double regret = second - best;

                if (regret > mejorRegret) {
                    mejorRegret = regret;
                    mejorPaquete = id;
                    mejorRuta = factibles.get(0).ruta;
                }
            }

            if (mejorPaquete == null) {
                break;
            }

            Paquete paquete = datos.getPaquetePorId(mejorPaquete);
            estado.reservarRutaSiFactible(
                    paquete,
                    mejorRuta,
                    PlanificacionUtils.getCreacionUtc(paquete, datos, config),
                    datos,
                    config
            );
            propuesta.put(mejorPaquete, mejorRuta);
            pendientes.remove(mejorPaquete);
        }
    }

    /** Pase final: intenta asignar TODOS los paquetes que no tienen ruta.
     *  Prioriza paquetes con MENOS rutas alternativas (mas restringidos primero). */
    private Map<String, Ruta> repararSinRutas(
            Map<String, Ruta> propuestaBase,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos
    ) {
        Map<String, Ruta> propuesta = new HashMap<>(propuestaBase);
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());

        List<Paquete> sinRuta = new ArrayList<>();
        for (Paquete paquete : paquetes) {
            if (propuesta.containsKey(paquete.getId())) continue;
            sinRuta.add(paquete);
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

        for (Paquete paquete : sinRuta) {
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
            List<Ruta> rutas = candidatos.getOrDefault(paquete.getId(), List.of());
            if (rutas.isEmpty()) continue;
            conCandidatos++;

            Ruta mejor = null;
            double mejorScore = Double.POSITIVE_INFINITY;
            for (Ruta ruta : rutas) {
                EstadoOperacional prueba = estado.copia();
                if (!prueba.reservarRutaSiFactible(paquete, ruta, creacion, datos, config)) continue;
                double score = PlanificacionUtils.evaluarRutaIndividual(paquete, ruta, estado, datos, config);
                if (score < mejorScore) { mejorScore = score; mejor = ruta; }
            }
            if (mejor != null) {
                estado.reservarRutaSiFactible(paquete, mejor, creacion, datos, config);
                propuesta.put(paquete.getId(), mejor);
                asignadosExtra++;
            }
        }

        System.out.println(String.format(Locale.ROOT, "  [ALNS] reparacionSinRutas: totalSinRuta=%d, conCandidatos=%d, asignadosExtra=%d",
                totalSinRuta, conCandidatos, asignadosExtra));
        return propuesta;
    }

    private List<RutaScore> evaluarRutasFactibles(
            Paquete paquete,
            List<Ruta> rutas,
            EstadoOperacional estado,
            Map<String, Ruta> propuestaBase,
            Dataset datos,
            Config_Simulacion config,
            Set<String> vuelosBloqueados
    ) {
        List<RutaScore> resultado = new ArrayList<>();
        LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);

        for (Ruta ruta : rutas) {
            if (rutaContieneVueloBloqueado(ruta, vuelosBloqueados)) {
                continue;
            }
            EstadoOperacional prueba = estado.copia();
            boolean factible = prueba.reservarRutaSiFactible(paquete, ruta, creacion, datos, config);
            if (!factible) {
                continue;
            }
            // Score con penalización de congestión usando el estado actual
            double scoreCongestion = PlanificacionUtils.evaluarRutaIndividual(paquete, ruta, estado, datos, config);
            resultado.add(new RutaScore(ruta, scoreCongestion));
        }

        resultado.sort(Comparator.comparingDouble(r -> r.score));
        return resultado;
    }

    private boolean rutaContieneVueloBloqueado(Ruta ruta, Set<String> vuelosBloqueados) {
        if (vuelosBloqueados == null || vuelosBloqueados.isEmpty()) {
            return false;
        }
        for (Vuelo vuelo : ruta.getVuelos()) {
            if (vuelosBloqueados.contains(vuelo.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean debeAceptarPeorPorAnnealing(Solucion solucionCandidata, Solucion solucionActual, double temperatura) {
        double delta = solucionCandidata.getCostoTotal() - solucionActual.getCostoTotal();
        double probAceptacion = Math.exp(-delta / Math.max(1e-9, temperatura));
        return random.nextDouble() < probAceptacion;
    }

    private int seleccionarPorRuleta(double[] pesos) {
        double suma = 0.0;
        for (double peso : pesos) {
            suma += peso;
        }
        // Ruleta: operadores con mejor desempeño histórico tienen mayor probabilidad de ser elegidos.
        double ticket = random.nextDouble() * suma;
        double acumulado = 0.0;
        for (int i = 0; i < pesos.length; i++) {
            acumulado += pesos[i];
            if (ticket <= acumulado) {
                return i;
            }
        }
        return pesos.length - 1;
    }

    private void actualizarPesos(double[] pesos, double[] puntajes, int[] usos, double tasaAprendizaje) {
        for (int i = 0; i < pesos.length; i++) {
            if (usos[i] > 0) {
                double promedio = puntajes[i] / usos[i];
                pesos[i] = (1.0 - tasaAprendizaje) * pesos[i] + tasaAprendizaje * Math.max(0.1, promedio);
            }
            puntajes[i] = 0.0;
            usos[i] = 0;
        }
    }

    private static class RutaScore {
        private final Ruta ruta;
        private final double score;

        private RutaScore(Ruta ruta, double score) {
            this.ruta = ruta;
            this.score = score;
        }
    }

    private static class RupturaResultado {
        private final List<String> paquetesImpactados;
        private final Set<String> vuelosBloqueados;

        private RupturaResultado(List<String> paquetesImpactados, Set<String> vuelosBloqueados) {
            this.paquetesImpactados = paquetesImpactados;
            this.vuelosBloqueados = vuelosBloqueados;
        }

        private List<String> getPaquetesImpactados() {
            return paquetesImpactados;
        }

        private Set<String> getVuelosBloqueados() {
            return vuelosBloqueados;
        }
    }
}
