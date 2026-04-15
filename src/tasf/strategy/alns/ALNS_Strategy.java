package tasf.strategy.alns;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.core.Solucion;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorStrategy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

            Set<String> removidos = aplicarRuptura(
                    operadorRuptura,
                    propuestaActual,
                    solucionActual,
                    datos,
                    config
            );

            Map<String, Ruta> propuestaCandidata = new HashMap<>(propuestaActual);
            for (String idRemovido : removidos) {
                propuestaCandidata.remove(idRemovido);
            }

            aplicarReparacion(
                    operadorReparacion,
                    propuestaCandidata,
                    removidos,
                    datos,
                    config,
                    candidatos
            );

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
                double delta = solucionCandidata.getCostoTotal() - solucionActual.getCostoTotal();
                double probAceptacion = Math.exp(-delta / Math.max(1e-9, temperatura));
                if (random.nextDouble() < probAceptacion) {
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
                actualizarPesos(
                        pesosRuptura,
                        puntajesRuptura,
                        usosRuptura,
                        config.getTasaAprendizajePesos()
                );
                actualizarPesos(
                        pesosReparacion,
                        puntajesReparacion,
                        usosReparacion,
                        config.getTasaAprendizajePesos()
                );
            }

            temperatura *= 0.995;
        }

        Solucion salida = PlanificacionUtils.evaluarAsignacion("ALNS", propuestaMejor, datos, config);
        salida.setMetrica("pesoRupturaRandom", pesosRuptura[RUPTURA_RANDOM]);
        salida.setMetrica("pesoRupturaWorstDelay", pesosRuptura[RUPTURA_WORST_DELAY]);
        salida.setMetrica("pesoReparacionGreedy", pesosReparacion[REPARACION_GREEDY]);
        salida.setMetrica("pesoReparacionRegret", pesosReparacion[REPARACION_REGRET]);
        return salida;
    }

    private Map<String, Ruta> construirInicialGreedy(
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos
    ) {
        Map<String, Ruta> propuesta = new HashMap<>();
        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());
        paquetes.sort(Comparator.comparing(p -> PlanificacionUtils.getCreacionUtc(p, datos, config)));
        EstadoOperacional estado = new EstadoOperacional();

        for (Paquete paquete : paquetes) {
            List<RutaScore> evaluadas = evaluarRutasFactibles(paquete, candidatos.getOrDefault(paquete.getId(), List.of()), estado, datos, config);
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

    private Set<String> aplicarRuptura(
            int operador,
            Map<String, Ruta> propuestaActual,
            Solucion solucionActual,
            Dataset datos,
            Config_Simulacion config
    ) {
        int tam = Math.max(1, propuestaActual.size());
        int cantidad = Math.max(1, (int) Math.ceil(tam * config.getPorcentajeRuptura()));

        if (operador == RUPTURA_WORST_DELAY) {
            return rupturaWorstDelay(propuestaActual, solucionActual, datos, config, cantidad);
        }
        return rupturaRandom(propuestaActual, cantidad);
    }

    private Set<String> rupturaRandom(Map<String, Ruta> propuestaActual, int cantidad) {
        List<String> ids = new ArrayList<>(propuestaActual.keySet());
        Collections.shuffle(ids, random);
        Set<String> removidos = new HashSet<>();
        for (int i = 0; i < Math.min(cantidad, ids.size()); i++) {
            removidos.add(ids.get(i));
        }
        return removidos;
    }

    private Set<String> rupturaWorstDelay(
            Map<String, Ruta> propuestaActual,
            Solucion solucionActual,
            Dataset datos,
            Config_Simulacion config,
            int cantidad
    ) {
        List<String> ids = new ArrayList<>(propuestaActual.keySet());
        ids.sort((a, b) -> Double.compare(
                scoreDeterioro(b, propuestaActual, solucionActual, datos, config),
                scoreDeterioro(a, propuestaActual, solucionActual, datos, config)
        ));

        Set<String> removidos = new HashSet<>();
        for (String id : ids) {
            if (removidos.size() >= cantidad) {
                break;
            }
            removidos.add(id);
        }
        return removidos;
    }

    private double scoreDeterioro(
            String paqueteId,
            Map<String, Ruta> propuestaActual,
            Solucion solucionActual,
            Dataset datos,
            Config_Simulacion config
    ) {
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

    private void aplicarReparacion(
            int operador,
            Map<String, Ruta> propuesta,
            Set<String> removidos,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos
    ) {
        if (operador == REPARACION_REGRET) {
            reparacionRegret(propuesta, removidos, datos, config, candidatos);
            return;
        }
        reparacionGreedy(propuesta, removidos, datos, config, candidatos);
    }

    private void reparacionGreedy(
            Map<String, Ruta> propuesta,
            Set<String> removidos,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos
    ) {
        List<String> ids = new ArrayList<>(removidos);
        ids.sort(Comparator.comparing(id -> PlanificacionUtils.getCreacionUtc(datos.getPaquetePorId(id), datos, config)));

        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
        for (String id : ids) {
            Paquete paquete = datos.getPaquetePorId(id);
            List<RutaScore> factibles = evaluarRutasFactibles(
                    paquete,
                    candidatos.getOrDefault(id, List.of()),
                    estado,
                    datos,
                    config
            );
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

    private void reparacionRegret(
            Map<String, Ruta> propuesta,
            Set<String> removidos,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos
    ) {
        Set<String> pendientes = new HashSet<>(removidos);
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);

        while (!pendientes.isEmpty()) {
            String mejorPaquete = null;
            Ruta mejorRuta = null;
            double mejorRegret = Double.NEGATIVE_INFINITY;

            for (String id : pendientes) {
                Paquete paquete = datos.getPaquetePorId(id);
                List<RutaScore> factibles = evaluarRutasFactibles(
                        paquete,
                        candidatos.getOrDefault(id, List.of()),
                        estado,
                        datos,
                        config
                );
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
            Dataset datos,
            Config_Simulacion config
    ) {
        List<RutaScore> resultado = new ArrayList<>();
        LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);

        for (Ruta ruta : rutas) {
            EstadoOperacional prueba = estado.copia();
            boolean factible = prueba.reservarRutaSiFactible(paquete, ruta, creacion, datos, config);
            if (!factible) {
                continue;
            }
            resultado.add(new RutaScore(ruta, scoreRuta(paquete, ruta, datos, config)));
        }

        resultado.sort(Comparator.comparingDouble(r -> r.score));
        return resultado;
    }

    private double scoreRuta(Paquete paquete, Ruta ruta, Dataset datos, Config_Simulacion config) {
        LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
        LocalDateTime limite = creacion.plus(PlanificacionUtils.getPlazoObjetivo(paquete, datos, config));
        long tardanza = Math.max(0L, Duration.between(limite, ruta.getLlegadaUtc()).toMinutes());
        long llegada = ruta.getLlegadaUtc().toEpochSecond(ZoneOffset.UTC);
        return llegada + (tardanza * 1000.0) + (ruta.getCantidadSaltos() * 50.0);
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
}
