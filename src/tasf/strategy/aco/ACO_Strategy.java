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
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        Map<String, List<Ruta>> candidatosPodados = podarCandidatosPorLlegada(candidatos, config.getTopRutasACO());
        Map<Ruta, Set<String>> cacheIdsVuelosRuta = new IdentityHashMap<>();

        Map<Tramo, Double> feromonas = new HashMap<>();
        for (Tramo tramo : datos.getTodosLosTramos()) {
            feromonas.put(tramo, 1.0);
        }

        Map<String, Ruta> mejorGlobalPropuesta = new HashMap<>();
        Solucion mejorGlobal = PlanificacionUtils.evaluarAsignacion("ACO", mejorGlobalPropuesta, datos, config);

        int iteraciones = Math.max(1, config.getIteracionesACO());
        // Parámetros para Lévy flights y reinicio
        double levyMu = 0.0; // media de Lévy
        double levySigma = 1.0; // dispersión de Lévy (aumenta con estancamiento)
        int sinMejora = 0;
        int umbralReinicio = Math.max(5, iteraciones / 4);
        int reinicios = 0;
        final int MAX_REINICIOS = 3;

        // Iteración ACO: varias hormigas construyen soluciones y luego se actualizan feromonas.
        for (int iter = 0; iter < iteraciones; iter++) {
            List<SolucionHormiga> solucionesIter = new ArrayList<>();

            int hormigas = Math.max(1, config.getHormigasACO());
            List<Map<String, Ruta>> propuestasHormigas = new ArrayList<>(hormigas);

            // Perturbación Lévy: añadir ruido gaussiano a feromonas para explorar
            Map<Tramo, Double> feromonasBase = new HashMap<>(feromonas);
            if (levySigma > 1.0) {
                levyPerturbarFeromonas(feromonasBase, levyMu, levySigma, random);
            }

            // Construir soluciones de hormigas en paralelo (son independientes)
            List<Callable<Map<String, Ruta>>> tareas = new ArrayList<>(hormigas);
            for (int ant = 0; ant < hormigas; ant++) {
                final Map<Tramo, Double> feroCopy = new HashMap<>(feromonasBase);
                tareas.add(() -> construirSolucionHormiga(datos, config, candidatosPodados, feroCopy, new Random(random.nextLong())));
            }
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(hormigas, Runtime.getRuntime().availableProcessors())
            );
            try {
                List<Future<Map<String, Ruta>>> futures = pool.invokeAll(tareas);
                for (Future<Map<String, Ruta>> f : futures) {
                    propuestasHormigas.add(f.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                for (int ant = 0; ant < hormigas; ant++) {
                    propuestasHormigas.add(construirSolucionHormiga(datos, config, candidatosPodados, feromonas));
                }
            } catch (ExecutionException e) {
                for (int ant = 0; ant < hormigas; ant++) {
                    propuestasHormigas.add(construirSolucionHormiga(datos, config, candidatosPodados, feromonas));
                }
            } finally {
                pool.shutdown();
            }

            // Post-proceso secuencial: reencaminamiento y evaluación
            for (Map<String, Ruta> propuestaHormiga : propuestasHormigas) {
                Set<String> vuelosBloqueados = seleccionarVuelosBloqueados(propuestaHormiga, config);
                if (!vuelosBloqueados.isEmpty()) {
                    propuestaHormiga = reencaminarPorVuelosBloqueados(
                            propuestaHormiga,
                            vuelosBloqueados,
                            datos,
                            config,
                            candidatosPodados,
                            feromonas,
                            cacheIdsVuelosRuta
                    );
                }
                Solucion solHormiga = PlanificacionUtils.evaluarAsignacion("ACO", propuestaHormiga, datos, config);
                solucionesIter.add(new SolucionHormiga(propuestaHormiga, solHormiga));
            }

            if (solucionesIter.isEmpty()) {
                sinMejora++;
                continue;
            }

            solucionesIter.sort(Comparator.comparingDouble(h -> h.solucion().getCostoTotal()));
            SolucionHormiga mejorIter = solucionesIter.get(0);
            if (mejorIter.solucion().getCostoTotal() < mejorGlobal.getCostoTotal()) {
                mejorGlobalPropuesta = new HashMap<>(mejorIter.propuesta());
                mejorGlobal = mejorIter.solucion();
                sinMejora = 0;
            } else {
                sinMejora++;
            }

            evaporarFeromonas(feromonas, config.getEvaporacionFeromona());

            // Reinicio controlado si estancamiento prolongado
            if (sinMejora >= umbralReinicio && reinicios < MAX_REINICIOS) {
                reiniciarFeromonasParcial(feromonas, config);
                reinicios++;
                sinMejora = 0;
                levySigma = 2.0; // reiniciar con alta exploración
            }

            int elite = Math.min(Math.max(1, config.getHormigasEliteACO()), solucionesIter.size());
            for (int i = 0; i < elite; i++) {
                SolucionHormiga eliteHormiga = solucionesIter.get(i);
                double pesoRanking = (elite - i) / (double) elite;
                double factorElite = config.getFactorEliteACO() * pesoRanking;
                depositarFeromonas(
                        feromonas,
                        eliteHormiga.propuesta(),
                        eliteHormiga.solucion(),
                        config.getDepositoFeromonaQ(),
                        factorElite
                );
            }

            if (!mejorGlobalPropuesta.isEmpty()) {
                depositarFeromonas(
                        feromonas,
                        mejorGlobalPropuesta,
                        mejorGlobal,
                        config.getDepositoFeromonaQ(),
                        config.getFactorGlobalBestACO()
                );
            }
        }

        Solucion salida = PlanificacionUtils.evaluarAsignacion("ACO", mejorGlobalPropuesta, datos, config);
        Map<String, Ruta> refinada = refinarSolucionLocal(mejorGlobalPropuesta, candidatosPodados, datos, config);
        Solucion salidaRefinada = PlanificacionUtils.evaluarAsignacion("ACO", refinada, datos, config);
        if (salidaRefinada.getCostoTotal() < salida.getCostoTotal()) {
            salida = salidaRefinada;
            mejorGlobalPropuesta = refinada;
        }
        double minFer = Double.POSITIVE_INFINITY;
        double maxFer = Double.NEGATIVE_INFINITY;
        for (double valor : feromonas.values()) {
            minFer = Math.min(minFer, valor);
            maxFer = Math.max(maxFer, valor);
        }
        salida.setMetrica("feromonaMinima", minFer == Double.POSITIVE_INFINITY ? 0.0 : minFer);
        salida.setMetrica("feromonaMaxima", maxFer == Double.NEGATIVE_INFINITY ? 0.0 : maxFer);
        salida.setMetrica("topRutasACO", config.getTopRutasACO());
        salida.setMetrica("hormigasEliteACO", config.getHormigasEliteACO());
        return salida;
    }

    private Map<String, Ruta> refinarSolucionLocal(
            Map<String, Ruta> propuestaInicial,
            Map<String, List<Ruta>> candidatos,
            Dataset datos,
            Config_Simulacion config
    ) {
        Map<String, Ruta> mejor = new HashMap<>(propuestaInicial);
        double mejorCosto = PlanificacionUtils.evaluarAsignacion("ACO", mejor, datos, config).getCostoTotal();
        Map<String, Ruta> referenciaOrden = new HashMap<>(mejor);

        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());
        paquetes.sort((a, b) -> {
            Ruta rutaB = referenciaOrden.get(b.getId());
            Ruta rutaA = referenciaOrden.get(a.getId());
            double costoB = rutaB == null ? Double.MAX_VALUE : rutaB.getHorasTotalesDesde(PlanificacionUtils.getCreacionUtc(b, datos, config));
            double costoA = rutaA == null ? Double.MAX_VALUE : rutaA.getHorasTotalesDesde(PlanificacionUtils.getCreacionUtc(a, datos, config));
            return Double.compare(costoB, costoA);
        });

        for (Paquete paquete : paquetes) {
            List<Ruta> rutasPaquete = candidatos.getOrDefault(paquete.getId(), List.of());
            Ruta rutaActual = mejor.get(paquete.getId());
            for (Ruta alternativa : rutasPaquete) {
                if (rutaActual != null && rutaActual.equals(alternativa)) {
                    continue;
                }

                Map<String, Ruta> tentativa = new HashMap<>(mejor);
                tentativa.put(paquete.getId(), alternativa);
                Solucion solucionTentativa = PlanificacionUtils.evaluarAsignacion("ACO", tentativa, datos, config);
                if (solucionTentativa.getCostoTotal() < mejorCosto) {
                    mejor = tentativa;
                    mejorCosto = solucionTentativa.getCostoTotal();
                    rutaActual = alternativa;
                }
            }
        }

        return mejor;
    }

    private Map<String, List<Ruta>> podarCandidatosPorLlegada(Map<String, List<Ruta>> candidatos, int topN) {
        if (topN <= 0) {
            return candidatos;
        }

        Map<String, List<Ruta>> podados = new HashMap<>();
        for (Map.Entry<String, List<Ruta>> entry : candidatos.entrySet()) {
            List<Ruta> rutas = new ArrayList<>(entry.getValue());
            rutas.sort(Comparator.comparing(Ruta::getLlegadaUtc).thenComparingInt(Ruta::getCantidadSaltos));
            if (rutas.size() > topN) {
                rutas = new ArrayList<>(rutas.subList(0, topN));
            }
            podados.put(entry.getKey(), rutas);
        }
        return podados;
    }

    private Map<String, Ruta> construirSolucionHormiga(
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos,
            Map<Tramo, Double> feromonas,
            Random rng
    ) {
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

                // Score con penalización de congestión
                double costoLocal = PlanificacionUtils.evaluarRutaIndividual(paquete, ruta, estado, datos, config);
                double desirability = calcularDeseabilidad(paquete, ruta, datos, config, feromonas, costoLocal);
                factibles.add(new RutaProb(ruta, desirability));
                total += desirability;
            }

            if (factibles.isEmpty()) {
                continue;
            }

            Ruta elegida = seleccionarRutaProbabilistica(factibles, total, rng);
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

    private Map<String, Ruta> construirSolucionHormiga(
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos,
            Map<Tramo, Double> feromonas
    ) {
        return construirSolucionHormiga(datos, config, candidatos, feromonas, this.random);
    }

    private double calcularDeseabilidad(
            Paquete paquete,
            Ruta ruta,
            Dataset datos,
            Config_Simulacion config,
            Map<Tramo, Double> feromonas,
            double costoGlobal
    ) {
        // Penalización drástica si fuera de plazo: desirabilidad casi cero
        if (PlanificacionUtils.estaFueraDePlazo(paquete, ruta, datos, config)) {
            return 1e-9;
        }

        // Recomendado para convergencia en este problema: alpha~=0.9 y beta~=3.2.
        double alpha = Math.max(0.5, config.getAlphaACO());
        double beta = Math.max(2.5, config.getBetaACO());

        double tau = 1.0;
        for (Tramo tramo : ruta.getTramos()) {
            tau *= Math.pow(feromonas.getOrDefault(tramo, 1.0), alpha);
        }

        double etaLocal = calcularVisibilidad(paquete, ruta, datos, config);
        double etaGlobal = 1.0 / (1.0 + Math.max(0.0, costoGlobal));
        double eta = Math.pow(Math.max(1e-9, etaLocal * etaGlobal), beta);
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

    private Ruta seleccionarRutaProbabilistica(List<RutaProb> rutas, double total, Random rng) {
        double ticket = rng.nextDouble() * total;
        double acumulado = 0.0;
        for (RutaProb rp : rutas) {
            acumulado += rp.prob;
            if (ticket <= acumulado) {
                return rp.ruta;
            }
        }
        return rutas.get(rutas.size() - 1).ruta;
    }

    private Ruta seleccionarRutaProbabilistica(List<RutaProb> rutas, double total) {
        return seleccionarRutaProbabilistica(rutas, total, this.random);
    }

    private Set<String> seleccionarVuelosBloqueados(Map<String, Ruta> propuesta, Config_Simulacion config) {
        if (propuesta.isEmpty()) {
            return Set.of();
        }

        Map<String, Integer> usoVuelo = new HashMap<>();
        for (Ruta ruta : propuesta.values()) {
            for (Vuelo vuelo : ruta.getVuelos()) {
                usoVuelo.put(vuelo.getId(), usoVuelo.getOrDefault(vuelo.getId(), 0) + 1);
            }
        }

        if (usoVuelo.isEmpty()) {
            return Set.of();
        }

        int cantidad = Math.max(1, (int) Math.ceil(usoVuelo.size() * config.getPorcentajeRuptura()));
        List<Map.Entry<String, Integer>> vuelosOrdenados = new ArrayList<>(usoVuelo.entrySet());
        vuelosOrdenados.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        Set<String> bloqueados = new HashSet<>();
        for (int i = 0; i < Math.min(cantidad, vuelosOrdenados.size()); i++) {
            bloqueados.add(vuelosOrdenados.get(i).getKey());
        }
        return bloqueados;
    }

    private Map<String, Ruta> reencaminarPorVuelosBloqueados(
            Map<String, Ruta> propuestaBase,
            Set<String> vuelosBloqueados,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos,
            Map<Tramo, Double> feromonas,
            Map<Ruta, Set<String>> cacheIdsVuelosRuta
    ) {
        Map<String, Ruta> propuesta = new HashMap<>(propuestaBase);
        Set<String> paquetesImpactados = new HashSet<>();

        for (Map.Entry<String, Ruta> entry : propuestaBase.entrySet()) {
            if (rutaContieneVueloBloqueado(entry.getValue(), vuelosBloqueados, cacheIdsVuelosRuta)) {
                paquetesImpactados.add(entry.getKey());
            }
        }

        for (String paqueteId : paquetesImpactados) {
            propuesta.remove(paqueteId);
        }

        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignaciones(propuesta, datos, config);
        List<Paquete> pendientes = new ArrayList<>();
        for (String id : paquetesImpactados) {
            pendientes.add(datos.getPaquetePorId(id));
        }
        pendientes.sort(Comparator.comparing(p -> PlanificacionUtils.getCreacionUtc(p, datos, config)));

        for (Paquete paquete : pendientes) {
            Ruta alternativa = seleccionarAlternativaFactible(
                    paquete,
                    estado,
                    propuesta,
                    candidatos.getOrDefault(paquete.getId(), List.of()),
                    vuelosBloqueados,
                    datos,
                    config,
                    feromonas,
                    cacheIdsVuelosRuta
            );
            if (alternativa == null) {
                continue;
            }

            estado.reservarRutaSiFactible(
                    paquete,
                    alternativa,
                    PlanificacionUtils.getCreacionUtc(paquete, datos, config),
                    datos,
                    config
            );
            propuesta.put(paquete.getId(), alternativa);
        }

        return propuesta;
    }

    private Ruta seleccionarAlternativaFactible(
            Paquete paquete,
            EstadoOperacional estado,
            Map<String, Ruta> propuestaBase,
            List<Ruta> candidatas,
            Set<String> vuelosBloqueados,
            Dataset datos,
            Config_Simulacion config,
            Map<Tramo, Double> feromonas,
            Map<Ruta, Set<String>> cacheIdsVuelosRuta
    ) {
        Ruta mejor = null;
        double mejorDesirability = Double.NEGATIVE_INFINITY;
        LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(paquete, datos, config);

        for (Ruta ruta : candidatas) {
            if (rutaContieneVueloBloqueado(ruta, vuelosBloqueados, cacheIdsVuelosRuta)) {
                continue;
            }

            EstadoOperacional prueba = estado.copia();
            boolean ok = prueba.reservarRutaSiFactible(paquete, ruta, creacion, datos, config);
            if (!ok) {
                continue;
            }

            // Score con penalización de congestión
            double costoLocal = PlanificacionUtils.evaluarRutaIndividual(paquete, ruta, estado, datos, config);
            double desirability = calcularDeseabilidad(paquete, ruta, datos, config, feromonas, costoLocal);
            if (desirability > mejorDesirability) {
                mejorDesirability = desirability;
                mejor = ruta;
            }
        }

        return mejor;
    }

    private boolean rutaContieneVueloBloqueado(Ruta ruta, Set<String> vuelosBloqueados, Map<Ruta, Set<String>> cacheIdsVuelosRuta) {
        if (vuelosBloqueados == null || vuelosBloqueados.isEmpty()) {
            return false;
        }

        Set<String> idsRuta = cacheIdsVuelosRuta.computeIfAbsent(ruta, this::extraerIdsVuelos);
        if (vuelosBloqueados.size() <= idsRuta.size()) {
            for (String idBloqueado : vuelosBloqueados) {
                if (idsRuta.contains(idBloqueado)) {
                    return true;
                }
            }
            return false;
        }

        for (String idRuta : idsRuta) {
            if (vuelosBloqueados.contains(idRuta)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extraerIdsVuelos(Ruta ruta) {
        Set<String> ids = new HashSet<>();
        for (Vuelo vuelo : ruta.getVuelos()) {
            ids.add(vuelo.getId());
        }
        return ids;
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
            double q,
            double factor
    ) {
        double deposito = (q * Math.max(0.1, factor) * 1000.0) / Math.max(1.0, solucion.getCostoTotal());

        for (Ruta ruta : propuesta.values()) {
            for (Tramo tramo : ruta.getTramos()) {
                double actual = feromonas.getOrDefault(tramo, 1.0);
                feromonas.put(tramo, actual + deposito);
            }
        }
    }

    /** Perturbación Lévy: añade ruido a las feromonas para escapar de óptimos locales */
    private void levyPerturbarFeromonas(Map<Tramo, Double> feromonas, double mu, double sigma, Random rng) {
        for (Map.Entry<Tramo, Double> entry : feromonas.entrySet()) {
            double ruido = levySample(mu, sigma, rng);
            double nuevo = entry.getValue() * (1.0 + ruido * 0.1);
            entry.setValue(Math.max(0.01, nuevo));
        }
    }

    /** Muestreo de distribución de Lévy (aproximación via transformada de Chambers-Mallows-Stuck) */
    private double levySample(double mu, double sigma, Random rng) {
        // α=1.5 para heavy-tailed behavior: pasos pequeños frecuentes + saltos grandes raros
        double alpha = 1.5;
        double u = rng.nextDouble() * Math.PI;
        double v = -Math.log(rng.nextDouble() + 1e-10);
        double w = Math.pow(Math.sin(u) / Math.pow(v, 1.0 / alpha), 1.0 / (alpha - 1.0))
                * Math.sin(u - (1.0 / alpha) * Math.atan2(Math.sin(u), v));
        return mu + sigma * w;
    }

    /** Reinicio parcial de feromonas: mantener estructura pero forzar exploración */
    private void reiniciarFeromonasParcial(Map<Tramo, Double> feromonas, Config_Simulacion config) {
        double minFer = Double.MAX_VALUE;
        double maxFer = -Double.MAX_VALUE;
        for (double v : feromonas.values()) {
            if (v < minFer) minFer = v;
            if (v > maxFer) maxFer = v;
        }
        double range = maxFer - minFer;
        if (range < 1e-10) range = 1.0;

        // Compresión: (valor - min) / range → aplana el landscape
        double compresion = 0.6; // mantener 60% de la estructura original
        double base = 1.0;
        for (Map.Entry<Tramo, Double> entry : feromonas.entrySet()) {
            double normalizado = (entry.getValue() - minFer) / range;
            double comprimido = base + normalizado * range * compresion;
            // Añadir ruido aleatorio para explorar
            double ruido = (random.nextDouble() - 0.5) * range * 0.2;
            entry.setValue(Math.max(0.05, comprimido + ruido));
        }
    }

    private record SolucionHormiga(Map<String, Ruta> propuesta, Solucion solucion) {
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
