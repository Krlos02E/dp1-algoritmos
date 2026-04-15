package tasf.strategy.aco;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.core.Solucion;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Tramo;
import tasf.model.Vuelo;
import tasf.strategy.PlanificadorStrategy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ACO_Strategy implements PlanificadorStrategy {
    private final Random random;

    public ACO_Strategy() {
        this(System.nanoTime());
    }

    public ACO_Strategy(long semilla) {
        this.random = new Random(semilla);
    }

    @Override
    public Solucion planificar(Dataset datos, Config_Simulacion config) {
        RouteFinder finder = new RouteFinder(datos);
        Map<String, List<Ruta>> candidatos = PlanificacionUtils.construirCandidatosRutas(datos, config, finder);

        Map<Tramo, Double> feromonas = new HashMap<>();
        for (Tramo tramo : datos.getTodosLosTramos()) {
            feromonas.put(tramo, 1.0);
        }

        Map<String, Ruta> mejorGlobalPropuesta = new HashMap<>();
        Solucion mejorGlobal = PlanificacionUtils.evaluarAsignacion("ACO", mejorGlobalPropuesta, datos, config);

        int iteraciones = Math.max(1, config.getIteracionesACO());
        // Iteración ACO: varias hormigas construyen soluciones y luego se actualizan feromonas.
        for (int iter = 0; iter < iteraciones; iter++) {
            Map<String, Ruta> mejorIterPropuesta = null;
            Solucion mejorIter = null;

            int hormigas = Math.max(1, config.getHormigasACO());
            for (int ant = 0; ant < hormigas; ant++) {
                Map<String, Ruta> propuestaHormiga = construirSolucionHormiga(datos, config, candidatos, feromonas);
                Solucion solHormiga = PlanificacionUtils.evaluarAsignacion("ACO", propuestaHormiga, datos, config);
                if (mejorIter == null || solHormiga.getCostoTotal() < mejorIter.getCostoTotal()) {
                    mejorIter = solHormiga;
                    mejorIterPropuesta = propuestaHormiga;
                }
            }

            evaporarFeromonas(feromonas, config.getEvaporacionFeromona());
            if (mejorIterPropuesta != null && mejorIter != null) {
                depositarFeromonas(feromonas, mejorIterPropuesta, mejorIter, config.getDepositoFeromonaQ());
                if (mejorIter.getCostoTotal() < mejorGlobal.getCostoTotal()) {
                    mejorGlobal = mejorIter;
                    mejorGlobalPropuesta = new HashMap<>(mejorIterPropuesta);
                }
            }
        }

        Solucion salida = PlanificacionUtils.evaluarAsignacion("ACO", mejorGlobalPropuesta, datos, config);
        double minFer = Double.POSITIVE_INFINITY;
        double maxFer = Double.NEGATIVE_INFINITY;
        for (double valor : feromonas.values()) {
            minFer = Math.min(minFer, valor);
            maxFer = Math.max(maxFer, valor);
        }
        salida.setMetrica("feromonaMinima", minFer == Double.POSITIVE_INFINITY ? 0.0 : minFer);
        salida.setMetrica("feromonaMaxima", maxFer == Double.NEGATIVE_INFINITY ? 0.0 : maxFer);
        return salida;
    }

    private Map<String, Ruta> construirSolucionHormiga(
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos,
            Map<Tramo, Double> feromonas
    ) {
        Map<String, Ruta> propuesta = new HashMap<>();
        EstadoOperacional estado = new EstadoOperacional();
        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());
        paquetes.sort(Comparator.comparing(p -> PlanificacionUtils.getCreacionUtc(p, datos, config)));

        for (Paquete paquete : paquetes) {
            List<Ruta> rutasPaquete = candidatos.getOrDefault(paquete.getId(), List.of());
            List<RutaProb> factibles = new ArrayList<>();
            double total = 0.0;

            for (Ruta ruta : rutasPaquete) {
                EstadoOperacional prueba = estado.copia();
                boolean ok = prueba.reservarRutaSiFactible(
                        paquete,
                        ruta,
                        PlanificacionUtils.getCreacionUtc(paquete, datos, config),
                        datos,
                        config
                );
                if (!ok) {
                    continue;
                }

                double desirability = calcularDeseabilidad(
                        paquete,
                        ruta,
                        datos,
                        config,
                        feromonas
                );
                factibles.add(new RutaProb(ruta, desirability));
                total += desirability;
            }

            if (factibles.isEmpty()) {
                continue;
            }

            Ruta elegida = seleccionarPorRuleta(factibles, total);
            estado.reservarRutaSiFactible(
                    paquete,
                    elegida,
                    PlanificacionUtils.getCreacionUtc(paquete, datos, config),
                    datos,
                    config
            );
            propuesta.put(paquete.getId(), elegida);
        }

        return propuesta;
    }

    private double calcularDeseabilidad(
            Paquete paquete,
            Ruta ruta,
            Dataset datos,
            Config_Simulacion config,
            Map<Tramo, Double> feromonas
    ) {
        double tau = 1.0;
        for (Tramo tramo : ruta.getTramos()) {
            tau *= Math.pow(feromonas.getOrDefault(tramo, 1.0), config.getAlphaACO());
        }

        double etaBase = calcularVisibilidad(paquete, ruta, datos, config);
        double eta = Math.pow(Math.max(1e-9, etaBase), config.getBetaACO());
        return Math.max(1e-12, tau * eta);
    }

    private double calcularVisibilidad(Paquete paquete, Ruta ruta, Dataset datos, Config_Simulacion config) {
        LocalDateTime instante = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
        double visibilidad = 1.0;

        for (Vuelo vuelo : ruta.getVuelos()) {
            long esperaMinutos = Duration.between(instante, vuelo.getSalidaUtc()).toMinutes();
            if (esperaMinutos < 0) {
                return 1e-9;
            }

            // Visibilidad: combina cercanía al destino y calidad de conexión (menor espera = mejor).
            int saltosRestantes = datos.distanciaEnSaltos(
                    vuelo.getDestino().getCodigoOACI(),
                    paquete.getDestinoOACI()
            );
            double cercaniaDestino = saltosRestantes == Integer.MAX_VALUE
                    ? 0.01
                    : 1.0 / (1.0 + saltosRestantes);

            double factorConexion = 1.0 / (1.0 + (esperaMinutos / 60.0));
            visibilidad *= Math.max(1e-9, cercaniaDestino * factorConexion);

            instante = vuelo.getLlegadaUtc();
        }

        return visibilidad;
    }

    private Ruta seleccionarPorRuleta(List<RutaProb> rutas, double total) {
        double ticket = random.nextDouble() * total;
        double acumulado = 0.0;
        for (RutaProb rp : rutas) {
            acumulado += rp.prob;
            if (ticket <= acumulado) {
                return rp.ruta;
            }
        }
        return rutas.get(rutas.size() - 1).ruta;
    }

    private void evaporarFeromonas(Map<Tramo, Double> feromonas, double evaporacion) {
        for (Map.Entry<Tramo, Double> entry : feromonas.entrySet()) {
            // Evaporación evita convergencia prematura y permite explorar rutas nuevas.
            double nuevo = entry.getValue() * (1.0 - evaporacion);
            entry.setValue(Math.max(0.01, nuevo));
        }
    }

    private void depositarFeromonas(
            Map<Tramo, Double> feromonas,
            Map<String, Ruta> propuesta,
            Solucion solucion,
            double q
    ) {
        // Depósito refuerza los tramos usados por la mejor hormiga de la iteración.
        double deposito = q / Math.max(1.0, solucion.getCostoTotal());

        for (Ruta ruta : propuesta.values()) {
            for (Tramo tramo : ruta.getTramos()) {
                double actual = feromonas.getOrDefault(tramo, 1.0);
                feromonas.put(tramo, actual + deposito);
            }
        }
    }

    private static class RutaProb {
        private final Ruta ruta;
        private final double prob;

        private RutaProb(Ruta ruta, double prob) {
            this.ruta = ruta;
            this.prob = prob;
        }
    }
}
