package tasf.experiments;

import tasf.config.Config_Simulacion;
import tasf.core.CapacidadDiariaCalculadora;
import tasf.core.ColapsoDetector;
import tasf.core.Dataset;
import tasf.core.DistribucionEnviosPorDia;
import tasf.core.PlanificacionUtils;
import tasf.core.Solucion;
import tasf.io.DatasetTextoLoader;
import tasf.io.DatasetTextoLoader.RutasDataset;
import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorRutasStrategy;
import tasf.strategy.TwoPhaseOrchestrator;
import tasf.strategy.aco.ACO_RutasPlanner;
import tasf.strategy.alns.ALNS_RutasPlanner;
import tasf.strategy.flow.MinCostFlowAsignador;
import tasf.model.ResultadoEnvio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Pipeline estandar de experimentacion.
 *
 * Hace el flujo completo solicitado:
 * - Calcula capacidad maxima diaria
 * - Genera niveles de carga 20%-70%
 * - Selecciona dias por numero de envios
 * - Ejecuta ALNS y ACO por separado
 * - Detecta colapso del sistema
 * - Exporta resultados listos para ANOVA o graficas
 * - Usa Min-Cost Flow como asignador unico
 */
public final class StandardExperimentPipeline {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path dataDir;
    private final LocalDate fechaInicioVuelos;
    private final int diasVuelos;
    private final int maxEnviosPorArchivo;
    private final int corridasPorAlgoritmo;
    private final LocalDate fechaEnviosFiltro;
    private final boolean usarDiaMaximoEnvios;
    private final int fechaEnviosDia;
    private final boolean barrerPorcentajeEnvios;
    private final int porcentajeEnviosInicial;
    private final int porcentajeEnviosMinimo;
    private final int pasoPorcentajeEnvios;
    private final List<AlgorithmSpec> algoritmos;
    private final boolean ejecucionRapida;

    public StandardExperimentPipeline(
            Path dataDir,
            LocalDate fechaInicioVuelos,
            int diasVuelos,
            int maxEnviosPorArchivo,
            int corridasPorAlgoritmo,
            LocalDate fechaEnviosFiltro,
            boolean usarDiaMaximoEnvios,
            int fechaEnviosDia,
            boolean barrerPorcentajeEnvios,
            int porcentajeEnviosInicial,
            int porcentajeEnviosMinimo,
            int pasoPorcentajeEnvios,
            List<AlgorithmSpec> algoritmos,
            boolean ejecucionRapida
    ) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir no puede ser null");
        this.fechaInicioVuelos = Objects.requireNonNull(fechaInicioVuelos, "fechaInicioVuelos no puede ser null");
        this.diasVuelos = diasVuelos;
        this.maxEnviosPorArchivo = Math.max(0, maxEnviosPorArchivo);
        this.corridasPorAlgoritmo = Math.max(1, corridasPorAlgoritmo);
        this.fechaEnviosFiltro = fechaEnviosFiltro;
        this.usarDiaMaximoEnvios = usarDiaMaximoEnvios;
        this.fechaEnviosDia = fechaEnviosDia;
        this.barrerPorcentajeEnvios = barrerPorcentajeEnvios;
        this.porcentajeEnviosInicial = porcentajeEnviosInicial;
        this.porcentajeEnviosMinimo = porcentajeEnviosMinimo;
        this.pasoPorcentajeEnvios = pasoPorcentajeEnvios;
        this.ejecucionRapida = ejecucionRapida;
        if (algoritmos == null || algoritmos.isEmpty()) {
            throw new IllegalArgumentException("Debe haber al menos un algoritmo");
        }
        this.algoritmos = List.copyOf(algoritmos);
    }

    public static StandardExperimentPipeline crearDefecto(Path dataDir, int corridasPorAlgoritmo) {
        return new StandardExperimentPipeline(
                dataDir,
                LocalDate.of(2026, 1, 2),
                0,  // 0 = cargar todos los vuelos (~1095 días), igual que el default de CLI
                0,
                corridasPorAlgoritmo,
                null,
                false,
                0, // fechaEnviosDia
                false,
                100,
                10,
                5,
                List.of(
                        new AlgorithmSpec("ALNS", () -> new ALNS_RutasPlanner(17L)),
                        new AlgorithmSpec("ACO", () -> new ACO_RutasPlanner(17L))
                ),
                false
        );
    }

    /** Devuelve los dias de horizonte de busqueda usados en config adaptativa */
    private int configHorizonteDias() {
        int diasDisponibles = diasVuelos > 0 ? diasVuelos : 1095;
        boolean muchosPaquetes = true; // Asumir worst-case
        boolean muchosVuelos = diasVuelos >= 100;
        if (muchosPaquetes && muchosVuelos) {
            return 14; // 336h
        }
        return Math.min(diasDisponibles, 10); // 240h = 10 dias max
    }

    public PipelineResult ejecutar() throws IOException {
        long tPipeline = System.nanoTime();
        PlanificacionUtils.limpiarCacheGlobal();

        // Cargar aeropuertos para conversión a UTC en escaneo liviano
        Path carpetaDatos = dataDir.toAbsolutePath().normalize();
        RutasDataset rutas = DatasetTextoLoader.resolverRutas(carpetaDatos);
        Map<String, Aeropuerto> aeropuertos = DatasetTextoLoader.cargarAeropuertos(rutas.archivoAeropuertos());

Map<LocalDate, Integer> conteoPorDia;
        long msScan;
        
        // Optimización: si se especifica día fijo, calcular fechaReferencia directamente
        LocalDate fechaReferencia = null;
        if (fechaEnviosDia > 0) {
            conteoPorDia = new HashMap<>();
            msScan = 0;
            LocalDate fechaInicioDefault = LocalDate.of(2026, 1, 1);
            fechaReferencia = fechaInicioDefault.plusDays(fechaEnviosDia - 1);
            System.out.println(String.format("Escaneo: día %d -> %s (optimizado)", fechaEnviosDia, fechaReferencia));
        } else {
            conteoPorDia = DatasetTextoLoader.escanearConteoPorDia(
                    resolverCarpetaEnvios(dataDir), aeropuertos
            );
            msScan = (System.nanoTime() - tPipeline) / 1_000_000;
            System.out.println(String.format(Locale.ROOT,
                    "Escaneo: %d días, %d envíos [%dms]",
                    conteoPorDia.size(),
                    conteoPorDia.values().stream().mapToInt(Integer::intValue).sum(),
                    msScan));
        }

        // Paso 2: Determinar qué fecha(s) se necesitan
        Set<LocalDate> fechasNecesarias = new HashSet<>();
        int capacidadMaximaEnviosDiaria = conteoPorDia.values().stream()
                .mapToInt(Integer::intValue).max().orElse(1);
        List<Integer> nivelesObjetivo;

        if (usarDiaMaximoEnvios || fechaEnviosFiltro != null || barrerPorcentajeEnvios || fechaEnviosDia > 0) {
            if (usarDiaMaximoEnvios || (barrerPorcentajeEnvios && fechaEnviosFiltro == null)) {
                fechaReferencia = conteoPorDia.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElseThrow(() -> new IllegalStateException("No hay días con envíos"));
            } else {
                // fechaReferencia ya calculado arriba para fechaEnviosDia > 0
                if (fechaReferencia == null) {
                    fechaReferencia = fechaEnviosFiltro;
                    if (!conteoPorDia.containsKey(fechaReferencia)) {
                        throw new IllegalArgumentException(
                                "No hay envíos para la fecha seleccionada: " + fechaEnviosFiltro);
                    }
                }
            }
            fechasNecesarias.add(fechaReferencia);

            if (barrerPorcentajeEnvios) {
                nivelesObjetivo = new ArrayList<>();
                for (int porcentaje = porcentajeEnviosInicial; porcentaje >= porcentajeEnviosMinimo; porcentaje -= pasoPorcentajeEnvios) {
                    nivelesObjetivo.add(porcentaje);
                }
                if (nivelesObjetivo.isEmpty()) {
                    throw new IllegalArgumentException("No se generaron porcentajes de barrido validos");
                }
            } else {
                int conteo = fechaEnviosDia > 0 ? 500 : conteoPorDia.getOrDefault(fechaReferencia, 0);
                nivelesObjetivo = List.of(conteo);
            }
        } else {
            GeneradorNivelesCarga generadorNiveles = new GeneradorNivelesCarga(capacidadMaximaEnviosDiaria);
            nivelesObjetivo = generadorNiveles.generarNivelesDefecto();

            List<DiaConDiferencia> candidatos = conteoPorDia.entrySet().stream()
                    .map(e -> new DiaConDiferencia(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingInt(DiaConDiferencia::getCount).reversed())
                    .toList();

            for (int nivel : nivelesObjetivo) {
                DiaConDiferencia mejor = null;
                int menorDif = Integer.MAX_VALUE;
                for (DiaConDiferencia dc : candidatos) {
                    int dif = Math.abs(dc.count - nivel);
                    if (dif < menorDif) {
                        menorDif = dif;
                        mejor = dc;
                    }
                }
                if (mejor != null) {
                    fechasNecesarias.add(mejor.fecha);
                }
            }
        }

        // Paso 3: Determinar ventana efectiva de vuelos centrada en las fechas de envio
        LocalDate fechaInicioEfectiva = fechaInicioVuelos;
        int diasVuelosEfectivos = diasVuelos;
        LocalDate ventanaFin = null;
        
        if (diasVuelos > 0 && !fechasNecesarias.isEmpty()) {
            LocalDate maxFecha = fechasNecesarias.stream().max(LocalDate::compareTo).orElseThrow();
            
            // Fix: si tenemos fechaEnviosDia específica, centrar ventana en esa fecha
            // con margen de 2 días antes y diasVuelos después
            if (fechaEnviosDia > 0) {
                // Ventana: fechaEnvios - 2 hasta fechaEnvios + diasVuelos
                fechaInicioEfectiva = maxFecha.minusDays(2);
                diasVuelosEfectivos = diasVuelos + 2;  // +2 días de margen
            } else {
                // Comportamiento original para múltiples fechas (barrerPorcentaje, etc.)
                long buffer = configHorizonteDias();
                long diasDesdeInicio = maxFecha.toEpochDay() - fechaInicioVuelos.toEpochDay();
                long diasRequeridos = diasDesdeInicio + buffer + 1;
                
                if (diasRequeridos <= diasVuelos) {
                    fechaInicioEfectiva = fechaInicioVuelos;
                    diasVuelosEfectivos = (int) diasRequeridos;
                } else {
                    fechaInicioEfectiva = fechaInicioVuelos;
                    diasVuelosEfectivos = (int) diasRequeridos;
                }
            }
            ventanaFin = fechaInicioEfectiva.plusDays(diasVuelosEfectivos - 1);
        } else if (fechaEnviosDia > 0 && !fechasNecesarias.isEmpty() && diasVuelos == 0) {
            // Fix: si diasVuelos no especificado (0) pero hay fechaEnviosDia,
            // usar default de 3 días con margen
            LocalDate maxFecha = fechasNecesarias.stream().max(LocalDate::compareTo).orElseThrow();
            fechaInicioEfectiva = maxFecha.minusDays(2);
            diasVuelosEfectivos = 3 + 2;  // default 3 + 2 margen = 5 días
            ventanaFin = fechaInicioEfectiva.plusDays(diasVuelosEfectivos - 1);
        }

        // Paso 4: Cargar dataset con la ventana calculada
        Dataset dataset = DatasetTextoLoader.cargarDataset(
                dataDir,
                fechaInicioEfectiva,
                diasVuelosEfectivos,
                maxEnviosPorArchivo,
                fechasNecesarias
        );
long msLoad = (System.nanoTime() - tPipeline) / 1_000_000;
        String ventanaStr = (ventanaFin != null)
                ? fechaInicioEfectiva + " a " + ventanaFin
                : "todos";
        System.out.println(String.format(Locale.ROOT,
                "[1/4] Paquetes: %d | Vuelos: %d (%s) [%dms]",
                dataset.getPaquetes().size(),
                dataset.getVuelos().size(),
                ventanaStr,
                msLoad));

        Config_Simulacion config = construirConfig(dataset.getPaquetes().size());

        System.out.println("[2/4] Algoritmo: " + algoritmos.get(0).name);

        DistribucionEnviosPorDia distribucionEnvios = new DistribucionEnviosPorDia(dataset.getPaquetes());
        CapacidadDiariaCalculadora calculadoraCapacidad = new CapacidadDiariaCalculadora(dataset.getVuelos());
        int capacidadMaximaDiaria = calculadoraCapacidad.estadisticas().maximo;

        DistribucionEnviosPorDia.DiaSeleccionado diaUnico = null;
        if (fechaReferencia != null) {
            diaUnico = new DistribucionEnviosPorDia.DiaSeleccionado(
                    fechaReferencia,
                    distribucionEnvios.obtenerEnviosDia(fechaReferencia),
                    distribucionEnvios.obtenerCantidadEnvios(fechaReferencia),
                    0
            );
            if (diaUnico.envios.isEmpty()) {
                throw new IllegalArgumentException("No hay envíos para la fecha: " + fechaReferencia);
            }
}

        System.out.println("[3/4] Algoritmo ejecutando...");

        List<RunRecord> rawRecords = new ArrayList<>();
        boolean encontradoUmbral = false;
        int corridaGlobal = 0;

        for (int nivelEnvios : nivelesObjetivo) {
            DistribucionEnviosPorDia.DiaSeleccionado dia = diaUnico != null
                    ? diaUnico
                    : distribucionEnvios.encontrarDiaMasCercano(nivelEnvios);
            List<Paquete> paquetesTrabajo = barrerPorcentajeEnvios
                    ? seleccionarPaquetesPorcentaje(dia.envios, nivelEnvios, dataset, config)
                    : dia.envios;
            Dataset datasetDia = construirDatasetDia(dataset, paquetesTrabajo);
            int totalMaletasDia = paquetesTrabajo.stream().mapToInt(Paquete::getCantidad).sum();

            boolean cumpleTodo = true;
            for (AlgorithmSpec algoritmo : algoritmos) {
                for (int corrida = 1; corrida <= corridasPorAlgoritmo; corrida++) {
                    corridaGlobal++;
                    long t0 = System.nanoTime();
                    Solucion solucion = ejecutarAlgoritmo(algoritmo, datasetDia, config);
                    long duracionMs = (System.nanoTime() - t0) / 1_000_000;

                    List<ResultadoEnvio> resultados = construirResultadosEnvio(datasetDia, solucion, config);
                    boolean colapso = ColapsoDetector.hayColapso(resultados);
                    int totalEnvios = datasetDia.getPaquetes().size();
                    int asignados = solucion.getRutasAsignadas().size();
                    int noAsignados = solucion.getPaquetesNoAsignados().size();
                    int maletasFueraDePlazo = solucion.getMaletasFueraDePlazo();
                    double porcentajeExito = totalEnvios == 0 ? 0.0 : (100.0 * asignados) / totalEnvios;
                        boolean cumplePlazo = noAsignados == 0 && maletasFueraDePlazo == 0;
                        cumpleTodo = cumpleTodo && cumplePlazo;

                        if (maletasFueraDePlazo > 0) {
                            System.out.println("  [DIAG] FUERA DE PLAZO detected: " + maletasFueraDePlazo + " maletas");
                            diagnosticarPaquetes(datasetDia, solucion, config);
                        }

                    rawRecords.add(new RunRecord(
                            algoritmo.name,
                            totalMaletasDia,
                            nivelEnvios,
                            dia.fecha,
                            dia.cantidad,
                            corrida,
                            totalEnvios,
                            asignados,
                            noAsignados,
                            porcentajeExito,
                            maletasFueraDePlazo,
                            colapso,
                            solucion.getCostoTotal(),
                            duracionMs,
                            solucion
                    ));

                    String nivelStr = barrerPorcentajeEnvios
                            ? nivelEnvios + "%"
                            : String.valueOf(totalMaletasDia);
                    System.out.println(String.format(Locale.ROOT,
                            "  [%s] asignados=%d/%d fuera_plazo=%d colapso=%s costo=%.0f [%dms]",
                            algoritmo.name,
                            asignados, totalEnvios,
                            maletasFueraDePlazo,
                            colapso,
                            solucion.getCostoTotal(),
                            duracionMs));

                    if (barrerPorcentajeEnvios) {
                        System.out.println(String.format(Locale.ROOT,
                                "[BARRIDO] porcentaje=%d paquetes=%d cumple_plazo=%s",
                                nivelEnvios, totalEnvios, cumplePlazo));
                        if (totalEnvios <= 100 && (noAsignados > 0 || maletasFueraDePlazo > 0)) {
                            diagnosticarPaquetes(datasetDia, solucion, config);
                        }
                    }
                }
            }

            if (barrerPorcentajeEnvios && cumpleTodo) {
                encontradoUmbral = true;
                System.out.println("[BARRIDO] umbral encontrado en " + nivelEnvios + "% de paquetes seleccionados");
                break;
            }
        }

        if (barrerPorcentajeEnvios && !encontradoUmbral) {
            System.out.println("[BARRIDO] no se encontro un porcentaje que dejara todos los seleccionados dentro del plazo");
        }

        ResultTable rawTable = new ResultTable(rawRecords);
        ResultTable summaryTable = rawTable.resumir();

        Path outputDir = dataDir.resolve("output");
        Files.createDirectories(outputDir);
        String stamp = LocalDateTime.now().format(FILE_TS);

        long msTotal = (System.nanoTime() - tPipeline) / 1_000_000;

        Path jsonLog = outputDir.resolve("log_" + stamp + ".json");
        generarLogJson(jsonLog, rawRecords, dataset, config, fechaInicioEfectiva, ventanaFin);

        int totalPaquetes = 0;
        int totalMaletas = 0;
        int maletasAsignadas = 0;
        int sinAsignar = 0;
        int fueraDePlazo = 0;
        boolean hayColapso = false;
        double costoTotal = 0;
        long duracionMs = 0;
        int pedidosAsignados = 0;

        if (!rawRecords.isEmpty()) {
            RunRecord r = rawRecords.get(0);
            totalPaquetes = r.totalEnvios;
            totalMaletas = r.nivelCargaMaletas;
            sinAsignar = r.noAsignados;
            hayColapso = r.hayColapso;
            costoTotal = r.costoTotal;
            duracionMs = r.duracionMs;

            Dataset datasetDia = dataset;
            Map<String, Ruta> rutasAsignadas = r.solucion.getRutasAsignadas();
            int maletasAsignadasReal = 0;
            int maletasFueraPlazo = 0;
            int pedidosConRuta = 0;
            for (Paquete p : datasetDia.getPaquetes()) {
                Ruta ruta = rutasAsignadas.get(p.getId());
                if (ruta != null) {
                    pedidosConRuta++;
                    maletasAsignadasReal += p.getCantidad();
                    if (PlanificacionUtils.estaFueraDePlazo(p, ruta, datasetDia, config)) {
                        maletasFueraPlazo += p.getCantidad();
                    }
                }
            }
            maletasAsignadas = maletasAsignadasReal;
            fueraDePlazo = maletasFueraPlazo;
            pedidosAsignados = pedidosConRuta;
        }

        System.out.println(String.format(Locale.ROOT,
                "[4/4] Log: %s [%dms]",
                jsonLog.getFileName(), msTotal));

        return new PipelineResult(capacidadMaximaDiaria, nivelesObjetivo, rawTable, summaryTable, null, null,
                totalPaquetes, totalMaletas, maletasAsignadas, pedidosAsignados, sinAsignar, fueraDePlazo, hayColapso, costoTotal, duracionMs);
    }

    private Solucion ejecutarAlgoritmo(AlgorithmSpec algoritmo, Dataset datasetDia, Config_Simulacion config) {
        PlanificadorRutasStrategy planificador = algoritmo.strategyFactory.get();
        TwoPhaseOrchestrator orchestrator = new TwoPhaseOrchestrator(planificador, new MinCostFlowAsignador());
        return orchestrator.ejecutarFlujoCompleto(datasetDia, config);
    }

    private void diagnosticarPaquetes(Dataset datasetDia, Solucion solucion, Config_Simulacion config) {
        Map<String, Ruta> asignadas = solucion.getRutasAsignadas();
        List<Paquete> sinAsignar = new ArrayList<>();
        List<Paquete> fueraPlazo = new ArrayList<>();

        for (Paquete p : datasetDia.getPaquetes()) {
            Ruta ruta = asignadas.get(p.getId());
            if (ruta == null) {
                sinAsignar.add(p);
            } else if (PlanificacionUtils.estaFueraDePlazo(p, ruta, datasetDia, config)) {
                fueraPlazo.add(p);
            }
        }

        if (!sinAsignar.isEmpty()) {
            System.out.println("  [DIAG] NO ASIGNADOS (" + sinAsignar.size() + "):");
            for (Paquete p : sinAsignar) {
                String origen = p.getOrigenOACI();
                String destino = p.getDestinoOACI();
                int saltosMin = datasetDia.distanciaEnSaltos(origen, destino);
                System.out.println(String.format(Locale.ROOT,
                        "    %s | %s→%s | cant=%d | saltos_min=%d",
                        p.getId(), origen, destino, p.getCantidad(),
                        saltosMin == Integer.MAX_VALUE ? -1 : saltosMin));
            }
        }

        if (!fueraPlazo.isEmpty()) {
            System.out.println("  [DIAG] FUERA DE PLAZO (" + fueraPlazo.size() + "):");
            for (Paquete p : fueraPlazo) {
                Ruta ruta = asignadas.get(p.getId());
                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datasetDia, config);
                Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datasetDia, config);
                LocalDateTime deadline = creacion.plus(plazo);
                LocalDateTime llegada = ruta.getLlegadaUtc();
                long retrasoMin = Duration.between(deadline, llegada).toMinutes();
                boolean mismoContinente = plazo.equals(config.getPlazoMismoContinente());
                System.out.println(String.format(Locale.ROOT,
                        "    %s | %s→%s | cant=%d | escalas=%d | creado=%s | deadline=%s | llega=%s | retraso=%dm | %s",
                        p.getId(), p.getOrigenOACI(), p.getDestinoOACI(), p.getCantidad(),
                        ruta.getCantidadSaltos(),
                        creacion.toString(), deadline.toString(), llegada.toString(),
                        retrasoMin,
                        mismoContinente ? "MISMO_CONTINENTE(24h)" : "INTERCONTINENTAL(48h)"));

                // Mostrar ruta
                System.out.print("      Ruta: ");
                for (var v : ruta.getVuelos()) {
                    System.out.print(v.getId() + "(" + v.getSalidaUtc().toString() + "→" + v.getLlegadaUtc().toString() + ") ");
                }
                System.out.println();
            }
        }
    }

    private List<ResultadoEnvio> construirResultadosEnvio(Dataset datasetDia, Solucion solucion, Config_Simulacion config) {
        List<ResultadoEnvio> resultados = new ArrayList<>();
        for (Paquete paquete : datasetDia.getPaquetes()) {
            Ruta ruta = solucion.getRutasAsignadas().get(paquete.getId());
            if (ruta == null) {
                resultados.add(new ResultadoEnvio(false, false));
                continue;
            }
            boolean fueraDePlazo = PlanificacionUtils.estaFueraDePlazo(paquete, ruta, datasetDia, config);
            resultados.add(new ResultadoEnvio(true, !fueraDePlazo));
        }
        return resultados;
    }

    private Dataset construirDatasetDia(Dataset base, List<Paquete> paquetesDia) {
        return new Dataset(base.getAeropuertos(), base.getVuelos(), new ArrayList<>(paquetesDia));
    }

    private List<Paquete> seleccionarPaquetesPorcentaje(
            List<Paquete> paquetesDia,
            int porcentaje,
            Dataset base,
            Config_Simulacion config
    ) {
        List<Paquete> ordenados = new ArrayList<>(paquetesDia);
        ordenados.sort(Comparator
                .comparing((Paquete p) -> PlanificacionUtils.getCreacionUtc(p, base, config))
                .thenComparing(Paquete::getId));

        int total = ordenados.size();
        int cantidad = (int) Math.ceil(total * (porcentaje / 100.0));
        cantidad = Math.max(1, Math.min(total, cantidad));
        return new ArrayList<>(ordenados.subList(0, cantidad));
    }

    private Config_Simulacion construirConfig(int totalPaquetes) {
        Config_Simulacion config = new Config_Simulacion();
        config.setPlazoMismoContinente(Duration.ofHours(24));
        config.setPlazoIntercontinental(Duration.ofHours(48));
        config.setMinimaConexion(Duration.ofMinutes(30));

        if (ejecucionRapida) {
            config.setIteracionesALNS(20);
            config.setIteracionesACO(10);
            config.setHormigasACO(4);
            config.setMaxRutasPorPaquete(4);
            config.setTopRutasACO(2);
            config.setHormigasEliteACO(1);
            config.setHorizonteBusqueda(Duration.ofHours(48));
            config.setMaxEscalas(2);
            config.setVentanaActualizacionPesos(5);
            config.setEvaporacionFeromona(0.4);
        }

        int diasDisponibles = diasVuelos > 0 ? diasVuelos : 1095;
        long horasMaximas = Math.min(240L, diasDisponibles * 24L);
        if (!ejecucionRapida) {
            config.setHorizonteBusqueda(Duration.ofHours(horasMaximas));
        }
        int maxEscalas = diasDisponibles <= 3 ? 2 : 3;
        config.setMaxEscalas(maxEscalas);

        boolean muchosPaquetes = totalPaquetes > 5000;
        boolean muchosVuelos = diasVuelos >= 100;

        if (muchosPaquetes && muchosVuelos) {
            config.setMaxRutasPorPaquete(50);
            config.setHorizonteBusqueda(Duration.ofHours(2160)); // 90 días
            config.setIteracionesALNS(100);
            config.setIteracionesACO(50);
            config.setVentanaActualizacionPesos(5);
            config.setHormigasACO(16);
            config.setAlphaACO(0.8);
            config.setBetaACO(2.8);
            config.setEvaporacionFeromona(0.3);
            System.out.println(String.format(Locale.ROOT,
                    "  [ADAPTIVO] paquetes=%d vuelos=%d → ALNS=100, ACO=50, maxRutas=50, horizonte=90d",
                    totalPaquetes, diasVuelos * 2866));
        } else if (muchosPaquetes) {
            config.setMaxRutasPorPaquete(50);
            config.setIteracionesALNS(100);
            config.setIteracionesACO(50);
            config.setVentanaActualizacionPesos(5);
            System.out.println(String.format(Locale.ROOT,
                    "  [ADAPTIVO] paquetes=%d → ALNS=100, ACO=50, maxRutas=50",
                    totalPaquetes));
        }
        return config;
    }

    public static final class AlgorithmSpec {
        public final String name;
        public final Supplier<PlanificadorRutasStrategy> strategyFactory;

        public AlgorithmSpec(String name, Supplier<PlanificadorRutasStrategy> strategyFactory) {
            this.name = Objects.requireNonNull(name, "name no puede ser null");
            this.strategyFactory = Objects.requireNonNull(strategyFactory, "strategyFactory no puede ser null");
        }
    }

    public static final class RunRecord {
        public final String algoritmo;
        public final int nivelCargaMaletas;
        public final int objetivoEnvios;
        public final LocalDate fechaSeleccionada;
        public final int enviosDiaSeleccionado;
        public final int corrida;
        public final int totalEnvios;
        public final int asignados;
        public final int noAsignados;
        public final double porcentajeExito;
        public final int maletasFueraDePlazo;
        public final boolean hayColapso;
        public final double costoTotal;
        public final long duracionMs;
        public final Solucion solucion;

        public RunRecord(
                String algoritmo,
                int nivelCargaMaletas,
                int objetivoEnvios,
                LocalDate fechaSeleccionada,
                int enviosDiaSeleccionado,
                int corrida,
                int totalEnvios,
                int asignados,
                int noAsignados,
                double porcentajeExito,
                int maletasFueraDePlazo,
                boolean hayColapso,
                double costoTotal,
                long duracionMs,
                Solucion solucion
        ) {
            this.algoritmo = algoritmo;
            this.nivelCargaMaletas = nivelCargaMaletas;
            this.objetivoEnvios = objetivoEnvios;
            this.fechaSeleccionada = fechaSeleccionada;
            this.enviosDiaSeleccionado = enviosDiaSeleccionado;
            this.corrida = corrida;
            this.totalEnvios = totalEnvios;
            this.asignados = asignados;
            this.noAsignados = noAsignados;
            this.porcentajeExito = porcentajeExito;
            this.maletasFueraDePlazo = maletasFueraDePlazo;
            this.hayColapso = hayColapso;
            this.costoTotal = costoTotal;
            this.duracionMs = duracionMs;
            this.solucion = solucion;
        }
    }

    public static final class SummaryRecord {
        public final String algoritmo;
        public final int nivelCargaMaletas;
        public final int corridas;
        public final double promedioExito;
        public final double tasaColapso;
        public final double promedioCosto;
        public final double promedioDuracionMs;
        public final double promedioNoAsignados;
        public final double promedioFueraPlazo;

        public SummaryRecord(
                String algoritmo,
                int nivelCargaMaletas,
                int corridas,
                double promedioExito,
                double tasaColapso,
                double promedioCosto,
                double promedioDuracionMs,
                double promedioNoAsignados,
                double promedioFueraPlazo
        ) {
            this.algoritmo = algoritmo;
            this.nivelCargaMaletas = nivelCargaMaletas;
            this.corridas = corridas;
            this.promedioExito = promedioExito;
            this.tasaColapso = tasaColapso;
            this.promedioCosto = promedioCosto;
            this.promedioDuracionMs = promedioDuracionMs;
            this.promedioNoAsignados = promedioNoAsignados;
            this.promedioFueraPlazo = promedioFueraPlazo;
        }
    }

    public static final class ResultTable {
        private final List<? extends Object> rows;
        private final boolean summary;

        private ResultTable(List<? extends Object> rows) {
            this(rows, false);
        }

        private ResultTable(List<? extends Object> rows, boolean summary) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.summary = summary;
        }

        public List<?> getRows() {
            return rows;
        }

        public ResultTable resumir() {
            Map<String, List<RunRecord>> grouped = new LinkedHashMap<>();
            for (Object row : rows) {
                RunRecord record = (RunRecord) row;
                String key = record.algoritmo + "|" + record.nivelCargaMaletas;
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            }

            List<SummaryRecord> summaryRows = new ArrayList<>();
            for (List<RunRecord> group : grouped.values()) {
                RunRecord base = group.get(0);
                int corridas = group.size();
                double promedioExito = group.stream().mapToDouble(r -> r.porcentajeExito).average().orElse(0.0);
                double tasaColapso = group.stream().filter(r -> r.hayColapso).count() / (double) corridas;
                double promedioCosto = group.stream().mapToDouble(r -> r.costoTotal).average().orElse(0.0);
                double promedioDuracion = group.stream().mapToLong(r -> r.duracionMs).average().orElse(0.0);
                double promedioNoAsignados = group.stream().mapToInt(r -> r.noAsignados).average().orElse(0.0);
                double promedioFueraPlazo = group.stream().mapToInt(r -> r.maletasFueraDePlazo).average().orElse(0.0);
                summaryRows.add(new SummaryRecord(
                        base.algoritmo,
                        base.nivelCargaMaletas,
                        corridas,
                        promedioExito,
                        tasaColapso,
                        promedioCosto,
                        promedioDuracion,
                        promedioNoAsignados,
                        promedioFueraPlazo
                ));
            }
            summaryRows.sort((a, b) -> {
                int cmp = Integer.compare(a.nivelCargaMaletas, b.nivelCargaMaletas);
                if (cmp != 0) {
                    return cmp;
                }
                return a.algoritmo.compareTo(b.algoritmo);
            });
            return new ResultTable(summaryRows, true);
        }

        public void writeCsv(Path outputCsv) throws IOException {
            Objects.requireNonNull(outputCsv, "outputCsv no puede ser null");
            List<String> lines = toCsvLines();
            Path parent = outputCsv.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(outputCsv, lines, StandardCharsets.UTF_8);
        }

public List<String> toCsvLines() {
            List<String> lines = new ArrayList<>();
            if (!summary) {
                lines.add("algoritmo,nivelCargaMaletas,objetivoEnvios,fechaSeleccionada,enviosDiaSeleccionado,corrida,totalEnvios,asignados,noAsignados,porcentajeExito,maletasFueraDePlazo,hayColapso,costoTotal,duracionMs");
                for (Object row : rows) {
                    RunRecord r = (RunRecord) row;
                    lines.add(String.format(Locale.ROOT,
                            "%s,%d,%d,%s,%d,%d,%d,%d,%d,%.6f,%d,%s,%.4f,%d",
                            escape(r.algoritmo),
                            r.nivelCargaMaletas,
                            r.objetivoEnvios,
                            r.fechaSeleccionada,
                            r.enviosDiaSeleccionado,
                            r.corrida,
                            r.totalEnvios,
                            r.asignados,
                            r.noAsignados,
                            r.porcentajeExito / 100.0,
                            r.maletasFueraDePlazo,
                            r.hayColapso,
                            r.costoTotal,
                            r.duracionMs
                    ));
                }
            } else {
                lines.add("algoritmo,nivelCargaMaletas,corridas,promedioExito,tasaColapso,promedioCosto,promedioDuracionMs,promedioNoAsignados,promedioFueraPlazo");
                for (Object row : rows) {
                    SummaryRecord r = (SummaryRecord) row;
                    lines.add(String.format(Locale.ROOT,
                            "%s,%d,%d,%.6f,%.6f,%.4f,%.4f,%.4f,%.4f",
                            escape(r.algoritmo),
                            r.nivelCargaMaletas,
                            r.corridas,
                            r.promedioExito,
                            r.tasaColapso,
                            r.promedioCosto,
                            r.promedioDuracionMs,
                            r.promedioNoAsignados,
                            r.promedioFueraPlazo));
                }
            }
            return lines;
        }

        private String escape(String value) {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return '"' + value.replace("\"", "\"\"") + '"';
            }
            return value;
        }
    }

    public static final class PipelineResult {
        public final int capacidadMaximaDiaria;
        public final List<Integer> nivelesObjetivo;
        public final ResultTable rawTable;
        public final ResultTable summaryTable;
        public final Path rawCsv;
        public final Path summaryCsv;
        public final int totalPaquetes;
        public final int totalMaletas;
        public final int maletasAsignadas;
        public final int pedidosAsignados;
        public final int sinAsignar;
        public final int fueraDePlazo;
        public final boolean hayColapso;
        public final double costoTotal;
        public final long duracionMs;

        public PipelineResult(
                int capacidadMaximaDiaria,
                List<Integer> nivelesObjetivo,
                ResultTable rawTable,
                ResultTable summaryTable,
                Path rawCsv,
                Path summaryCsv,
                int totalPaquetes,
                int totalMaletas,
                int maletasAsignadas,
                int pedidosAsignados,
                int sinAsignar,
                int fueraDePlazo,
                boolean hayColapso,
                double costoTotal,
                long duracionMs
        ) {
            this.capacidadMaximaDiaria = capacidadMaximaDiaria;
            this.nivelesObjetivo = Collections.unmodifiableList(new ArrayList<>(nivelesObjetivo));
            this.rawTable = rawTable;
            this.summaryTable = summaryTable;
            this.rawCsv = rawCsv;
            this.summaryCsv = summaryCsv;
            this.totalPaquetes = totalPaquetes;
            this.totalMaletas = totalMaletas;
            this.maletasAsignadas = maletasAsignadas;
            this.pedidosAsignados = pedidosAsignados;
            this.sinAsignar = sinAsignar;
            this.fueraDePlazo = fueraDePlazo;
            this.hayColapso = hayColapso;
            this.costoTotal = costoTotal;
            this.duracionMs = duracionMs;
        }
    }

    private static Path resolverCarpetaEnvios(Path dataDir) throws IOException {
        Path base = dataDir.toAbsolutePath().normalize();
        Path input = base.resolve("input");
        if (Files.isDirectory(input)) {
            Path envios = input.resolve("envios");
            if (Files.isDirectory(envios)) return envios;
        }
        Path enviosInput = base.resolve("envios");
        if (Files.isDirectory(enviosInput)) return enviosInput;
        Path enviosLegacy = base.resolve("_envios_preliminar_");
        if (Files.isDirectory(enviosLegacy)) return enviosLegacy;
        throw new IllegalArgumentException("No se pudo encontrar carpeta de envíos en: " + base);
    }

    private static final class DiaConDiferencia {
        final LocalDate fecha;
        final int count;

        DiaConDiferencia(LocalDate fecha, int count) {
            this.fecha = fecha;
            this.count = count;
        }

        int getCount() {
            return count;
        }
    }

    private void generarLogJson(Path outputPath, List<RunRecord> records, Dataset dataset, Config_Simulacion config,
                           LocalDate fechaInicioVuelos, LocalDate fechaFinVuelos) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        if (records.isEmpty()) {
            json.append("  \"error\": \"no hay resultados\"\n");
            json.append("}\n");
            Files.writeString(outputPath, json.toString(), StandardCharsets.UTF_8);
            return;
        }

        RunRecord record = records.get(0);
        String algoritmo = record.algoritmo;
        String fechaSeleccionada = String.valueOf(record.fechaSeleccionada);
        Solucion solucion = record != null ? record.solucion : null;

        int totalMaletas = record.nivelCargaMaletas;
        int totalPedidos = record.totalEnvios;
        int maletasAsignadas = 0;
        int pedidosAsignados = 0;
        int maletasFueraPlazo = 0;
        int noAsignados = record.noAsignados;
        boolean hayColapso = record.hayColapso;
        double costoTotal = record.costoTotal;
        long duracionMs = record.duracionMs;

        if (solucion != null && config != null) {
            Map<String, Ruta> rutas = solucion.getRutasAsignadas();
            for (Paquete p : dataset.getPaquetes()) {
                Ruta r = rutas.get(p.getId());
                if (r != null) {
                    pedidosAsignados++;
                    maletasAsignadas += p.getCantidad();
                    if (PlanificacionUtils.estaFueraDePlazo(p, r, dataset, config)) {
                        maletasFueraPlazo += p.getCantidad();
                    }
                }
            }
        }

        json.append("  \"metadata\": {\n");
        json.append("    \"algoritmo\": \"").append(algoritmo).append("\",\n");
        json.append("    \"maletasTotales\": ").append(totalMaletas).append(",\n");
        json.append("    \"maletasAsignadas\": ").append(maletasAsignadas).append(",\n");
        json.append("    \"pedidosTotales\": ").append(totalPedidos).append(",\n");
        json.append("    \"pedidosAsignados\": ").append(pedidosAsignados).append(",\n");
        json.append("    \"fechaSeleccionada\": \"").append(fechaSeleccionada).append("\",\n");
        json.append("    \"rangoDiasVuelos\": \"").append(fechaInicioVuelos).append(" a ").append(fechaFinVuelos).append("\",\n");
        json.append("    \"hayColapso\": ").append(hayColapso).append(",\n");
        json.append("    \"maletasFueraDePlazo\": ").append(maletasFueraPlazo).append(",\n");
        json.append("    \"pedidosSinAsignar\": ").append(noAsignados).append(",\n");
        json.append("    \"costoTotal\": ").append(String.format(Locale.ROOT, "%.2f", costoTotal)).append(",\n");
        json.append("    \"duracionMs\": ").append(duracionMs).append(",\n");
        json.append("    \"generado\": \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\"\n");
        json.append("  },\n");

        json.append("  \"asignaciones\": [\n");

        List<Paquete> paquetes;
        if (solucion != null && config != null) {
            Map<String, Ruta> rutasAsignadas = solucion.getRutasAsignadas();
            paquetes = dataset.getPaquetes();

            int count = 0;
            for (Paquete p : paquetes) {
                Ruta ruta = rutasAsignadas.get(p.getId());
                if (ruta == null) continue;

                if (count > 0) json.append(",\n");
                json.append("    {\n");
                json.append("      \"pedidoId\": \"").append(p.getId()).append("\",\n");
                json.append("      \"origen\": \"").append(p.getOrigenOACI()).append("\",\n");
                json.append("      \"destino\": \"").append(p.getDestinoOACI()).append("\",\n");

                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, dataset, config);
                json.append("      \"creacion\": \"").append(creacion.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("Z\",\n");

                Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, dataset, config);
                LocalDateTime deadline = creacion.plus(plazo);
                json.append("      \"deadline\": \"").append(deadline.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("Z\",\n");

                json.append("      \"paquetes\": ").append(p.getCantidad()).append(",\n");
                json.append("      \"ruta\": [\n");

                List<tasf.model.Vuelo> vuelos = ruta.getVuelos();
                for (int i = 0; i < vuelos.size(); i++) {
                    tasf.model.Vuelo v = vuelos.get(i);
                    if (i > 0) json.append(",\n");
                    json.append("        {\n");
                    json.append("          \"vueloId\": \"").append(v.getId()).append("\",\n");
                    json.append("          \"origen\": \"").append(v.getOrigen().getCodigoOACI()).append("\",\n");
                    json.append("          \"destino\": \"").append(v.getDestino().getCodigoOACI()).append("\",\n");
                    json.append("          \"salida\": \"").append(v.getSalidaUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("Z\",\n");
                    json.append("          \"llegada\": \"").append(v.getLlegadaUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("Z\"\n");
                    json.append("        }");
                }
                json.append("\n      ]\n");
                json.append("    }");
                count++;
            }
        } else {
            json.append("  ]\n");
            json.append("}\n");
            Files.writeString(outputPath, json.toString(), StandardCharsets.UTF_8);
            return;
        }

        json.append("\n  ]\n");
        json.append("}\n");

        Files.writeString(outputPath, json.toString(), StandardCharsets.UTF_8);
    }
}
