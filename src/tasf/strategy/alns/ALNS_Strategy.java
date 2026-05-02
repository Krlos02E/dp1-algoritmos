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

        Map<String, Ruta> propuestaActual = construirInicialGreedy(datos, config, candidatos);
        Solucion solucionActual = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaActual, datos, config);
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

        Solucion salida = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaMejor, datos, config);
        salida.setMetrica("pesoRupturaRandom", pesosRuptura[RUPTURA_RANDOM]);
        salida.setMetrica("pesoRupturaWorstDelay", pesosRuptura[RUPTURA_WORST_DELAY]);
        salida.setMetrica("pesoReparacionGreedy", pesosReparacion[REPARACION_GREEDY]);
        salida.setMetrica("pesoReparacionRegret", pesosReparacion[REPARACION_REGRET]);
        return salida;
    }

    private Map<String, Ruta> construirInicialGreedy(Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        Map<String, Ruta> propuesta = new HashMap<>();
        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());
        paquetes.sort(Comparator.comparing(p -> PlanificacionUtils.getCreacionUtc(p, datos, config)));
        EstadoOperacional estado = new EstadoOperacional();

        for (Paquete paquete : paquetes) {
            List<RutaScore> evaluadas = evaluarRutasFactibles(paquete, candidatos.getOrDefault(paquete.getId(), List.of()), estado, propuesta, datos, config, Set.of());
            if (!evaluadas.isEmpty()) {
                Ruta mejor = evaluadas.get(0).ruta;
                estado.reservarRutaSiFactible(
                        paquete,
                        mejor,
                        PlanificacionUtils.getCreacionUtc(paquete, datos, config),
                        datos,
                        config
                );
                propuesta.put(paquete.getId(), mejor);
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
