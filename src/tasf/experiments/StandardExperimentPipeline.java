package tasf.experiments;

import tasf.config.Config_Simulacion;
import tasf.core.CapacidadDiariaCalculadora;
import tasf.core.ColapsoDetector;
import tasf.core.Dataset;
import tasf.core.DistribucionEnviosPorDia;
import tasf.core.PlanificacionUtils;
import tasf.core.Solucion;
import tasf.io.DatasetTextoLoader;
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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private final boolean barrerPorcentajeEnvios;
    private final int porcentajeEnviosInicial;
    private final int porcentajeEnviosMinimo;
    private final int pasoPorcentajeEnvios;
    private final List<AlgorithmSpec> algoritmos;

    public StandardExperimentPipeline(
            Path dataDir,
            LocalDate fechaInicioVuelos,
            int diasVuelos,
            int maxEnviosPorArchivo,
            int corridasPorAlgoritmo,
            LocalDate fechaEnviosFiltro,
            boolean usarDiaMaximoEnvios,
            boolean barrerPorcentajeEnvios,
            int porcentajeEnviosInicial,
            int porcentajeEnviosMinimo,
            int pasoPorcentajeEnvios,
            List<AlgorithmSpec> algoritmos
    ) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir no puede ser null");
        this.fechaInicioVuelos = Objects.requireNonNull(fechaInicioVuelos, "fechaInicioVuelos no puede ser null");
        this.diasVuelos = diasVuelos;
        this.maxEnviosPorArchivo = Math.max(0, maxEnviosPorArchivo);
        this.corridasPorAlgoritmo = Math.max(1, corridasPorAlgoritmo);
        this.fechaEnviosFiltro = fechaEnviosFiltro;
        this.usarDiaMaximoEnvios = usarDiaMaximoEnvios;
        this.barrerPorcentajeEnvios = barrerPorcentajeEnvios;
        this.porcentajeEnviosInicial = porcentajeEnviosInicial;
        this.porcentajeEnviosMinimo = porcentajeEnviosMinimo;
        this.pasoPorcentajeEnvios = pasoPorcentajeEnvios;
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
                false,
                100,
                10,
                5,
                List.of(
                        new AlgorithmSpec("ALNS", () -> new ALNS_RutasPlanner(17L)),
                        new AlgorithmSpec("ACO", () -> new ACO_RutasPlanner(17L))
                )
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

        // Paso 1: Escaneo liviano para determinar fechas objetivo ANTES de cargar datos
        Map<LocalDate, Integer> conteoPorDia = DatasetTextoLoader.escanearConteoPorDia(
                resolverCarpetaEnvios(dataDir)
        );
        long msScan = (System.nanoTime() - tPipeline) / 1_000_000;
        System.out.println(String.format(Locale.ROOT,
                "[0/4] Escaneo liviano: %d días con envíos, %d envíos totales [%dms]",
                conteoPorDia.size(),
                conteoPorDia.values().stream().mapToInt(Integer::intValue).sum(),
                msScan));

        // Paso 2: Determinar qué fecha(s) se necesitan
        Set<LocalDate> fechasNecesarias = new HashSet<>();
        int capacidadMaximaEnviosDiaria = conteoPorDia.values().stream()
                .mapToInt(Integer::intValue).max().orElse(1);
        List<Integer> nivelesObjetivo;
        LocalDate fechaReferencia = null;

        if (usarDiaMaximoEnvios || fechaEnviosFiltro != null || barrerPorcentajeEnvios) {
            if (usarDiaMaximoEnvios || (barrerPorcentajeEnvios && fechaEnviosFiltro == null)) {
                fechaReferencia = conteoPorDia.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElseThrow(() -> new IllegalStateException("No hay días con envíos"));
            } else {
                fechaReferencia = fechaEnviosFiltro;
                if (!conteoPorDia.containsKey(fechaReferencia)) {
                    throw new IllegalArgumentException(
                            "No hay envíos para la fecha seleccionada: " + fechaEnviosFiltro);
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
                int conteo = conteoPorDia.getOrDefault(fechaReferencia, 0);
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
            long buffer = configHorizonteDias(); // dias extra despues del ultimo envio para horizonte
            long diasDesdeInicio = maxFecha.toEpochDay() - fechaInicioVuelos.toEpochDay();
            long diasRequeridos = diasDesdeInicio + buffer + 1;

            if (diasRequeridos <= diasVuelos) {
                // La ventana cabe: usar desde fechaInicioVuelos hasta maxFecha+buffer
                fechaInicioEfectiva = fechaInicioVuelos;
                diasVuelosEfectivos = (int) diasRequeridos;
            } else {
                // No cabe todo: usar los diasVuelos completos terminando en maxFecha+buffer
                ventanaFin = maxFecha.plusDays(buffer);
                fechaInicioEfectiva = ventanaFin.minusDays(diasVuelos - 1);
                diasVuelosEfectivos = diasVuelos;
            }
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
                ? " ventana=" + fechaInicioEfectiva + ".." + ventanaFin
                : " (todos los vuelos)";
        System.out.println(String.format(Locale.ROOT,
                "[1/4] Datos cargados: aeropuertos=%d, vuelos=%d, paquetes=%d%s [%dms]",
                dataset.getAeropuertos().size(),
                dataset.getVuelos().size(),
                dataset.getPaquetes().size(),
                ventanaStr,
                msLoad));

        Config_Simulacion config = construirConfig(dataset.getPaquetes().size());

        DistribucionEnviosPorDia distribucionEnvios = new DistribucionEnviosPorDia(dataset.getPaquetes());

        CapacidadDiariaCalculadora calculadoraCapacidad = new CapacidadDiariaCalculadora(dataset.getVuelos());
        int capacidadMaximaDiaria = calculadoraCapacidad.estadisticas().maximo;

        // DiaSeleccionado se usa en el loop; para modo filtrado es siempre el mismo día
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

        int totalCorridas = algoritmos.size() * corridasPorAlgoritmo * nivelesObjetivo.size();
        System.out.println(String.format(Locale.ROOT,
                "[2/4] Setup: capacidad_max=%d maletas/dia, niveles=%d, total_corridas=%d",
                capacidadMaximaDiaria, nivelesObjetivo.size(), totalCorridas));
        System.out.println(String.format(Locale.ROOT,
                "[3/4] Ejecutando %d algoritmos x %d corridas x %d niveles = %d corridas",
                algoritmos.size(), corridasPorAlgoritmo, nivelesObjetivo.size(), totalCorridas));

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
                            duracionMs
                    ));

                    String nivelStr = barrerPorcentajeEnvios
                            ? nivelEnvios + "%"
                            : String.valueOf(totalMaletasDia);
                    System.out.println(String.format(Locale.ROOT,
                            "  [%s] nivel=%s corrida=%d/%d → asignados=%d/%d fuera_plazo=%d colapso=%s costo=%.0f [%dms]",
                            algoritmo.name,
                            nivelStr,
                            corridaGlobal, totalCorridas,
                            asignados, totalEnvios,
                            maletasFueraDePlazo,
                            colapso,
                            solucion.getCostoTotal(),
                            duracionMs));

                    if (barrerPorcentajeEnvios) {
                        System.out.println(String.format(Locale.ROOT,
                                "[BARRIDO] porcentaje=%d paquetes=%d cumple_plazo=%s",
                                nivelEnvios, totalEnvios, cumplePlazo));
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
        Path rawCsv = outputDir.resolve("experimentos_raw_" + stamp + ".csv");
        Path summaryCsv = outputDir.resolve("experimentos_resumen_" + stamp + ".csv");

        rawTable.writeCsv(rawCsv);
        summaryTable.writeCsv(summaryCsv);

        long msTotal = (System.nanoTime() - tPipeline) / 1_000_000;
        System.out.println(String.format(Locale.ROOT,
                "[4/4] CSV exportados: raw=%s resumen=%s [%dms total]",
                rawCsv.getFileName(), summaryCsv.getFileName(), msTotal));

        return new PipelineResult(capacidadMaximaDiaria, nivelesObjetivo, rawTable, summaryTable, rawCsv, summaryCsv);
    }

    private Solucion ejecutarAlgoritmo(AlgorithmSpec algoritmo, Dataset datasetDia, Config_Simulacion config) {
        PlanificadorRutasStrategy planificador = algoritmo.strategyFactory.get();
        TwoPhaseOrchestrator orchestrator = new TwoPhaseOrchestrator(planificador, new MinCostFlowAsignador());
        return orchestrator.ejecutarFlujoCompleto(datasetDia, config);
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
        // Horizonte adaptativo: no buscar más allá de los vuelos cargados
        int diasDisponibles = diasVuelos > 0 ? diasVuelos : 1095;
        long horasMaximas = Math.min(240L, diasDisponibles * 24L);
        config.setHorizonteBusqueda(Duration.ofHours(horasMaximas));
        // Escalas adaptativas: con pocos días no hay tiempo para 3 conexiones
        int maxEscalas = diasDisponibles <= 3 ? 2 : 3;
        config.setMaxEscalas(maxEscalas);
        // Iteraciones y hormigas adaptativas: con muchos paquetes reducir para no explotar
        boolean muchosPaquetes = totalPaquetes > 5000;
        boolean muchosVuelos = diasVuelos >= 100;
        if (muchosPaquetes && muchosVuelos) {
            config.setMaxRutasPorPaquete(25);
            config.setHorizonteBusqueda(Duration.ofHours(720)); // 30 dias
            config.setIteracionesALNS(50);
            config.setIteracionesACO(25);
            config.setVentanaActualizacionPesos(5);
            System.out.println(String.format(Locale.ROOT,
                    "  [ADAPTIVO] paquetes=%d vuelos=%d → iteraciones=ALNS(50)/ACO(25), maxRutas=25, horizonte=30d",
                    totalPaquetes, diasVuelos * 2866));
        } else if (muchosPaquetes) {
            config.setMaxRutasPorPaquete(25);
            config.setHorizonteBusqueda(Duration.ofHours(720)); // 30 dias
            config.setIteracionesALNS(50);
            config.setIteracionesACO(25);
            config.setVentanaActualizacionPesos(5);
            System.out.println(String.format(Locale.ROOT,
                    "  [ADAPTIVO] paquetes=%d → iteraciones=ALNS(50)/ACO(25), maxRutas=25, horizonte=30d",
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
                long duracionMs
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
                            r.porcentajeExito,
                            r.maletasFueraDePlazo,
                            r.hayColapso,
                            r.costoTotal,
                            r.duracionMs));
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

        public PipelineResult(
                int capacidadMaximaDiaria,
                List<Integer> nivelesObjetivo,
                ResultTable rawTable,
                ResultTable summaryTable,
                Path rawCsv,
                Path summaryCsv
        ) {
            this.capacidadMaximaDiaria = capacidadMaximaDiaria;
            this.nivelesObjetivo = Collections.unmodifiableList(new ArrayList<>(nivelesObjetivo));
            this.rawTable = rawTable;
            this.summaryTable = summaryTable;
            this.rawCsv = rawCsv;
            this.summaryCsv = summaryCsv;
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
}
