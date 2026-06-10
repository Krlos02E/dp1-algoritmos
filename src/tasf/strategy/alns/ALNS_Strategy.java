package tasf.strategy.alns;

import tasf.config.Config_Simulacion;
import tasf.core.AsignacionPaquete;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.core.Solucion;
import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorStrategy;
import tasf.strategy.alns.operators.*;
import tasf.util.Log;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class ALNS_Strategy implements PlanificadorStrategy {

    private static final List<Ruta> EMPTY_RUTA_LIST = List.of();

    private static final double PEN_NO_ASIGNADO = 1_000_000.0;
    private static final double PEN_FUERA_PLAZO = 250_000.0;
    private static final double PEN_COLAPSO = 500_000.0;

    private final Random random;
    private final double[] pesosRuptura;
    private final double[] pesosReparacion;
    private final double[] puntajesRuptura;
    private final double[] puntajesReparacion;
    private final int[] usosRuptura;
    private final int[] usosReparacion;

    private final List<DestructionOperator> destructores;
    private final List<RepairOperator> reparadores;
    private final OperadorProfiler profiler;

    public ALNS_Strategy() { this(System.nanoTime()); }

    public ALNS_Strategy(long semilla) {
        this.random = new Random(semilla);
        this.profiler = new OperadorProfiler();

        this.destructores = new ArrayList<>();
        destructores.add(new RandomRemoval(random));
        destructores.add(new WorstDelayRemoval(random));
        destructores.add(new CongestionRemoval());
        destructores.add(new SlaBreachRemoval());

        this.reparadores = new ArrayList<>();
        reparadores.add(new GreedyRepair(random));
        reparadores.add(new RegretRepair());

        int numD = destructores.size();
        int numR = reparadores.size();
        this.pesosRuptura = new double[numD];
        this.pesosReparacion = new double[numR];
        this.puntajesRuptura = new double[numD];
        this.puntajesReparacion = new double[numR];
        this.usosRuptura = new int[numD];
        this.usosReparacion = new int[numR];
        Arrays.fill(pesosRuptura, 1.0);
        Arrays.fill(pesosReparacion, 1.0);
    }

    @Override
    public Solucion planificar(Dataset datos, Config_Simulacion config) {
        RouteFinder finder = new RouteFinder(datos);
        Map<String, List<Ruta>> candidatos = PlanificacionUtils.construirCandidatosRutas(datos, config, finder);

        Map<String, AsignacionPaquete> propuestaNormal = construirInicialGreedy(datos, config, candidatos);
        Solucion solucionActual = evaluarConCostosEstratificados("ALNS", propuestaNormal, datos, config);
        Map<String, AsignacionPaquete> propuestaMejor = copiarPropuesta(propuestaNormal);
        Solucion mejorSolucion = solucionActual;

        Map<String, AsignacionPaquete> propuestaActual = copiarPropuesta(propuestaNormal);

        double temperatura = Math.max(1.0, solucionActual.getCostoTotal() * 0.05);
        int sinMejora = 0;
        final int MAX_SIN_MEJORA = 5;
        final int EVAL_CADA = 10;
        final int MAX_REHEATS = 3;
        int reheats = 0;

        for (int iter = 1; iter <= Math.max(1, config.getIteracionesALNS()); iter++) {
            int opR = seleccionarPorRuleta(pesosRuptura);
            int opP = seleccionarPorRuleta(pesosReparacion);

            Map<String, AsignacionPaquete> candidata = copiarPropuesta(propuestaActual);
            int cantidad = Math.max(2, (int) Math.ceil(candidata.size() * config.getPorcentajeRuptura()));

            long tStart = System.nanoTime();
            Set<String> destruidos = destructores.get(opR).destroy(candidata, datos, config, candidatos, cantidad);
            long tMid = System.nanoTime();
            for (String id : destruidos) candidata.remove(id);

            EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignacionesSplit(candidata, datos, config);
            reparadores.get(opP).repair(candidata, new ArrayList<>(destruidos), datos, config, candidatos, estado);
            long tEnd = System.nanoTime();

            profiler.registrarDestroy(destructores.get(opR).nombre(), tMid - tStart);
            profiler.registrarRepair(reparadores.get(opP).nombre(), tEnd - tMid);

            intentarAsignarNoAsignados(candidata, datos, config, candidatos, 3);

            Solucion solCandidata = null;
            if (iter == 1 || iter % EVAL_CADA == 0) {
                solCandidata = evaluarConCostosEstratificados("ALNS", candidata, datos, config);
            }

            double recompensa = 0.0;
            if (solCandidata != null && solCandidata.getCostoTotal() < mejorSolucion.getCostoTotal()) {
                propuestaMejor = copiarPropuesta(candidata);
                mejorSolucion = solCandidata;
                propuestaActual = copiarPropuesta(candidata);
                solucionActual = solCandidata;
                recompensa = 6.0;
                sinMejora = 0;
            } else if (solCandidata != null && solCandidata.getCostoTotal() < solucionActual.getCostoTotal()) {
                propuestaActual = copiarPropuesta(candidata);
                solucionActual = solCandidata;
                recompensa = 3.0;
                sinMejora = 0;
            } else if (solCandidata != null && debeAceptarPeorPorAnnealing(solCandidata, solucionActual, temperatura)) {
                propuestaActual = copiarPropuesta(candidata);
                solucionActual = solCandidata;
                recompensa = 1.0;
            } else {
                sinMejora++;
            }

            if (sinMejora >= MAX_SIN_MEJORA) {
                boolean hayColapso = solucionActual.getPaquetesNoAsignados().size() > 0
                        || solucionActual.getMaletasFueraDePlazo() > 0;
                if (hayColapso && reheats < MAX_REHEATS) {
                    reheats++;
                    temperatura = Math.max(1.0, solucionActual.getCostoTotal() * 0.20);
                    sinMejora = 0;
                } else {
                    break;
                }
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

        propuestaMejor = forzarATiempo(propuestaMejor, datos, config, candidatos);
        propuestaMejor = faseLegalizacion(propuestaMejor, datos, config, candidatos, 3);

        diagnosticarPaquetesSinAsignar(propuestaMejor, datos, config, candidatos);

        Solucion salida = evaluarConCostosEstratificados("ALNS", propuestaMejor, datos, config);
        salida.setMetrica("pesoRupturaRandom", pesosRuptura[0]);
        salida.setMetrica("pesoRupturaWorstDelay", pesosRuptura[1]);
        salida.setMetrica("pesoRupturaCongestion", pesosRuptura[2]);
        salida.setMetrica("pesoRupturaSlaBreach", pesosRuptura[3]);
        salida.setMetrica("pesoReparacionGreedy", pesosReparacion[0]);
        salida.setMetrica("pesoReparacionRegret", pesosReparacion[1]);
        salida.setMetrica("reheats", reheats);

        int splits = 0;
        for (AsignacionPaquete ap : propuestaMejor.values()) {
            if (ap.cantidadRutas() > 1) splits++;
        }
        salida.setMetrica("paquetesConSplit", splits);

        for (Map.Entry<String, Double> e : profiler.getMetricas().entrySet()) {
            salida.setMetrica(e.getKey(), e.getValue());
        }

        return salida;
    }

    private Map<String, AsignacionPaquete> copiarPropuesta(Map<String, AsignacionPaquete> original) {
        Map<String, AsignacionPaquete> copia = new HashMap<>();
        for (Map.Entry<String, AsignacionPaquete> e : original.entrySet()) {
            copia.put(e.getKey(), e.getValue().copia());
        }
        return copia;
    }

    private Solucion evaluarConCostosEstratificados(String estrategia, Map<String, AsignacionPaquete> propuesta,
                                                    Dataset datos, Config_Simulacion config) {
        Solucion solucion = new Solucion(estrategia);
        EstadoOperacional estado = new EstadoOperacional();
        List<Paquete> paquetes = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            if (propuesta.containsKey(p.getId())) {
                paquetes.add(p);
            }
        }
        paquetes.sort((a, b) -> {
            AsignacionPaquete aa = propuesta.get(a.getId());
            AsignacionPaquete ab = propuesta.get(b.getId());
            int na = aa != null ? aa.cantidadRutas() : Integer.MAX_VALUE;
            int nb = ab != null ? ab.cantidadRutas() : Integer.MAX_VALUE;
            if (na != nb) return Integer.compare(na, nb);
            return PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config));
        });

        double horasAcumuladas = 0.0;

        for (Paquete paquete : paquetes) {
            AsignacionPaquete asignacion = propuesta.get(paquete.getId());
            if (asignacion == null || asignacion.isEmpty()) continue;

            LocalDateTime creacionUtc = PlanificacionUtils.getCreacionUtc(paquete, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(paquete, datos, config);
            LocalDateTime deadline = creacionUtc.plus(plazo);

            boolean factible = true;
            for (var rc : asignacion.getRutas()) {
                if (!estado.reservarRutaSiFactible(paquete, rc.getRuta(), creacionUtc, datos, config, rc.getCantidad())) {
                    factible = false;
                    break;
                }
            }

            if (!factible) {
                solucion.marcarNoAsignado(paquete.getId(), true);
                continue;
            }

            LocalDateTime ultimaLlegada = asignacion.getUltimaLlegada();
            boolean fueraDePlazo = ultimaLlegada != null && ultimaLlegada.isAfter(deadline);
            int cantidadAsignada = asignacion.cantidadAsignada();
            solucion.asignarSplit(paquete.getId(), asignacion, fueraDePlazo, cantidadAsignada);
            horasAcumuladas += asignacion.getHorasTotalesDesde(creacionUtc);
        }

        for (Paquete p : datos.getPaquetes()) {
            if (!propuesta.containsKey(p.getId()) && !solucion.getPaquetesNoAsignados().contains(p.getId())) {
                solucion.marcarNoAsignado(p.getId(), false);
            }
        }

        int noAsignados = solucion.getPaquetesNoAsignados().size();
        int totalMaletasNoAsignadas = 0;
        for (String id : solucion.getPaquetesNoAsignados()) {
            Paquete p = datos.getPaquetePorId(id);
            if (p != null) totalMaletasNoAsignadas += p.getCantidad();
        }

        double costo =
                (noAsignados * PEN_NO_ASIGNADO)
                        + (solucion.getMaletasFueraDePlazo() * PEN_FUERA_PLAZO)
                        + (solucion.getEventosColapso() * PEN_COLAPSO)
                        + horasAcumuladas;
        solucion.setCostoTotal(costo);
        solucion.setMetrica("paquetesNoAsignados", noAsignados);
        solucion.setMetrica("eventosColapso", solucion.getEventosColapso());
        return solucion;
    }

    private Map<String, AsignacionPaquete> faseLegalizacion(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                                                Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                                                int maxIntentos) {
        Set<String> incompletos = new HashSet<>();
        for (Paquete p : datos.getPaquetes()) {
            AsignacionPaquete asignacion = propuesta.get(p.getId());
            if (asignacion == null || asignacion.cantidadAsignada() < p.getCantidad()) {
                incompletos.add(p.getId());
            }
        }
        if (incompletos.isEmpty()) return propuesta;

        for (int intento = 0; intento < maxIntentos && !incompletos.isEmpty(); intento++) {
            EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignacionesSplit(propuesta, datos, config);
            List<String> pendientes = new ArrayList<>(incompletos);

            pendientes.sort((a, b) -> {
                int na = candidatos.getOrDefault(a, EMPTY_RUTA_LIST).size();
                int nb = candidatos.getOrDefault(b, EMPTY_RUTA_LIST).size();
                if (na != nb) return Integer.compare(na, nb);
                Paquete pa = datos.getPaquetePorId(a);
                Paquete pb = datos.getPaquetePorId(b);
                return PlanificacionUtils.getCreacionUtc(pa, datos, config)
                        .compareTo(PlanificacionUtils.getCreacionUtc(pb, datos, config));
            });

            int asignadosEsteIntento = 0;
            for (String id : pendientes) {
                Paquete p = datos.getPaquetePorId(id);
                if (p == null) continue;

                int yaAsignado = 0;
                AsignacionPaquete existente = propuesta.get(id);
                Set<Ruta> rutasUsadas = new HashSet<>();
                if (existente != null) {
                    yaAsignado = existente.cantidadAsignada();
                    for (var rc : existente.getRutas()) {
                        rutasUsadas.add(rc.getRuta());
                    }
                }
                int remanente = p.getCantidad() - yaAsignado;
                if (remanente <= 0) continue;

                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
                Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
                LocalDateTime deadline = creacion.plus(plazo);
                List<Ruta> rutas = candidatos.getOrDefault(id, EMPTY_RUTA_LIST);

                List<Ruta> aEvaluar = new ArrayList<>();
                for (Ruta r : rutas) {
                    if (!rutasUsadas.contains(r)) aEvaluar.add(r);
                }
                if (aEvaluar.isEmpty()) continue;

                aEvaluar.sort((r1, r2) -> Double.compare(
                        PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                        PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));

                if (existente == null) {
                    existente = new AsignacionPaquete(new ArrayList<>());
                } else {
                    existente = existente.copia();
                }

                int maxEval = Math.min(10, aEvaluar.size());
                for (int i = 0; i < maxEval && remanente > 0; i++) {
                    Ruta r = aEvaluar.get(i);
                    int capResidual = estado.capacidadResidualRuta(p, r, creacion, datos, config);
                    if (capResidual <= 0) continue;
                    int cantidadAsignar = Math.min(remanente, capResidual);
                    if (estado.reservarRutaSiFactible(p, r, creacion, datos, config, cantidadAsignar)) {
                        existente.agregarRuta(r, cantidadAsignar);
                        rutasUsadas.add(r);
                        remanente -= cantidadAsignar;
                        asignadosEsteIntento++;
                    }
                }

                if (!existente.isEmpty()) {
                    propuesta.put(id, existente);
                }
            }

            incompletos.removeIf(id -> {
                Paquete p = datos.getPaquetePorId(id);
                AsignacionPaquete a = propuesta.get(id);
                return p == null || (a != null && a.cantidadAsignada() >= p.getCantidad());
            });
            if (asignadosEsteIntento == 0) break;
        }

        return propuesta;
    }

    private Map<String, AsignacionPaquete> construirInicialGreedy(Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        Map<String, AsignacionPaquete> propuesta = new HashMap<>();
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
                if (!r.getLlegadaUtc().isAfter(deadline)) rutasValidas.add(r);
                else rutasFallback.add(r);
            }

            List<Ruta> aEvaluar = rutasValidas.isEmpty() ? rutasFallback : rutasValidas;
            if (aEvaluar.isEmpty()) continue;

            aEvaluar.sort((r1, r2) -> Double.compare(
                    PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                    PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));

            int remanente = p.getCantidad();
            AsignacionPaquete asignacion = new AsignacionPaquete(new ArrayList<>());
            Set<Ruta> usadas = new HashSet<>();

            int maxEval = Math.min(aEvaluar.size(), 10);
            for (int i = 0; i < maxEval && remanente > 0; i++) {
                Ruta r = aEvaluar.get(i);
                if (usadas.contains(r)) continue;

                int capacidadResidual = estado.capacidadResidualRuta(p, r, creacion, datos, config);
                if (capacidadResidual <= 0) continue;

                int cantidadAsignar = Math.min(remanente, capacidadResidual);
                if (estado.reservarRutaSiFactible(p, r, creacion, datos, config, cantidadAsignar)) {
                    asignacion.agregarRuta(r, cantidadAsignar);
                    usadas.add(r);
                    remanente -= cantidadAsignar;
                }
            }
            if (!asignacion.isEmpty()) {
                propuesta.put(p.getId(), asignacion);
            }
        }
        return propuesta;
    }

    private void intentarAsignarNoAsignados(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                                            Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                                            int maxIntentos) {
        List<Paquete> sinRuta = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            AsignacionPaquete asignacion = propuesta.get(p.getId());
            int yaAsignado = asignacion != null ? asignacion.cantidadAsignada() : 0;
            if (yaAsignado < p.getCantidad() && !candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST).isEmpty()) {
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

        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignacionesSplit(propuesta, datos, config);

        for (Paquete p : sinRuta) {
            AsignacionPaquete existente = propuesta.get(p.getId());
            int yaAsignado = existente != null ? existente.cantidadAsignada() : 0;
            int remanente = p.getCantidad() - yaAsignado;
            if (remanente <= 0) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);
            List<Ruta> rutas = candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST);

            Set<Ruta> rutasUsadas = new HashSet<>();
            if (existente != null) {
                for (var rc : existente.getRutas()) rutasUsadas.add(rc.getRuta());
            }

            List<Ruta> aEvaluar = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!rutasUsadas.contains(r)) aEvaluar.add(r);
            }
            if (aEvaluar.isEmpty()) continue;

            aEvaluar.sort((r1, r2) -> Double.compare(
                    PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                    PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));

            if (existente == null) {
                existente = new AsignacionPaquete(new ArrayList<>());
            } else {
                existente = existente.copia();
            }

            int maxEval = Math.min(10, aEvaluar.size());
            for (int i = 0; i < maxEval && remanente > 0; i++) {
                Ruta r = aEvaluar.get(i);
                int capResidual = estado.capacidadResidualRuta(p, r, creacion, datos, config);
                if (capResidual <= 0) continue;
                int cantidadAsignar = Math.min(remanente, capResidual);
                if (estado.reservarRutaSiFactible(p, r, creacion, datos, config, cantidadAsignar)) {
                    existente.agregarRuta(r, cantidadAsignar);
                    rutasUsadas.add(r);
                    remanente -= cantidadAsignar;
                }
            }

            if (!existente.isEmpty()) {
                propuesta.put(p.getId(), existente);
            }
        }
    }

    private Map<String, AsignacionPaquete> forzarATiempo(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                                             Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        EstadoOperacional estado = PlanificacionUtils.construirEstadoConAsignacionesSplit(propuesta, datos, config);
        int mejorados = 0;

        List<Paquete> paquetesConProblema = new ArrayList<>();
        for (Paquete p : datos.getPaquetes()) {
            AsignacionPaquete asignacion = propuesta.get(p.getId());
            if (asignacion == null || asignacion.isEmpty()) continue;
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime deadline = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));
            if (asignacion.getUltimaLlegada().isAfter(deadline)) {
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
            AsignacionPaquete asignacion = propuesta.get(p.getId());
            if (asignacion == null) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            LocalDateTime deadline = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));

            if (!asignacion.getUltimaLlegada().isAfter(deadline)) continue;

            List<Ruta> alternativas = candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST);
            for (Ruta alt : alternativas) {
                if (alt.getLlegadaUtc().isAfter(deadline)) continue;
                int capResidual = estado.capacidadResidualRuta(p, alt, creacion, datos, config);
                if (capResidual >= p.getCantidad() && estado.reservarRutaSiFactible(p, alt, creacion, datos, config, p.getCantidad())) {
                    propuesta.put(p.getId(), new AsignacionPaquete(alt, p.getCantidad()));
                    mejorados++;
                    break;
                }
            }
        }

        if (mejorados > 0) {
            Log.detail("  [ALNS] forzarATiempo: " + mejorados + " paquetes reasignados");
        }
        return propuesta;
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

    private void diagnosticarPaquetesSinAsignar(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                                                  Config_Simulacion config, Map<String, List<Ruta>> candidatos) {
        List<Paquete> sinCandidatas = new ArrayList<>();
        List<Paquete> conCandidatasSinCapacidad = new ArrayList<>();
        List<Paquete> parcialmenteAsignados = new ArrayList<>();
        List<Paquete> sinConectividad = new ArrayList<>();

        for (Paquete p : datos.getPaquetes()) {
            AsignacionPaquete asignacion = propuesta.get(p.getId());
            int asignado = asignacion != null ? asignacion.cantidadAsignada() : 0;
            int demanda = p.getCantidad();

            if (asignado >= demanda) continue;

            List<Ruta> candidatas = candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST);
            boolean alcanzable = datos.puedeLlegarA(p.getOrigenOACI(), p.getDestinoOACI(), config.getMaxEscalas());

            if (!alcanzable) {
                sinConectividad.add(p);
            } else if (candidatas.isEmpty()) {
                sinCandidatas.add(p);
            } else if (asignado == 0) {
                conCandidatasSinCapacidad.add(p);
            } else {
                parcialmenteAsignados.add(p);
            }
        }

        int total = sinCandidatas.size() + conCandidatasSinCapacidad.size() + parcialmenteAsignados.size() + sinConectividad.size();
        if (total == 0) return;

        Log.detail("  [DIAG-ALNS] PAQUETES INCOMPLETOS: " + total);

        if (!sinConectividad.isEmpty()) {
            Log.detail("    [SIN CONECTIVIDAD] " + sinConectividad.size() + " paquetes (destino inalcanzable):");
            for (Paquete p : sinConectividad) {
                Log.detail(String.format("      %s | %s→%s | cant=%d%n",
                        p.getId(), p.getOrigenOACI(), p.getDestinoOACI(), p.getCantidad()));
            }
        }

        if (!sinCandidatas.isEmpty()) {
            Log.detail("    [SIN RUTAS CANDIDATAS] " + sinCandidatas.size() + " paquetes (rutas fuera de deadline o ventana):");
            for (Paquete p : sinCandidatas) {
                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
                Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
                Log.detail(String.format("      %s | %s→%s | cant=%d | creado=%s | deadline=%s%n",
                        p.getId(), p.getOrigenOACI(), p.getDestinoOACI(), p.getCantidad(),
                        creacion, creacion.plus(plazo)));
            }
        }

        if (!conCandidatasSinCapacidad.isEmpty()) {
            Log.detail("    [CON CANDIDATAS PERO SIN CAPACIDAD] " + conCandidatasSinCapacidad.size() + " paquetes:");
            for (Paquete p : conCandidatasSinCapacidad) {
                List<Ruta> candidatas = candidatos.getOrDefault(p.getId(), EMPTY_RUTA_LIST);
                Log.detail(String.format("      %s | %s→%s | cant=%d | candidatas=%d%n",
                        p.getId(), p.getOrigenOACI(), p.getDestinoOACI(), p.getCantidad(), candidatas.size()));
            }
        }

        if (!parcialmenteAsignados.isEmpty()) {
            Log.detail("    [PARCIALMENTE ASIGNADOS] " + parcialmenteAsignados.size() + " paquetes:");
            for (Paquete p : parcialmenteAsignados) {
                AsignacionPaquete asignacion = propuesta.get(p.getId());
                int asignado = asignacion != null ? asignacion.cantidadAsignada() : 0;
                Log.detail(String.format("      %s | %s→%s | cant=%d | asignado=%d | faltante=%d%n",
                        p.getId(), p.getOrigenOACI(), p.getDestinoOACI(), p.getCantidad(), asignado, p.getCantidad() - asignado));
            }
        }
    }
}
