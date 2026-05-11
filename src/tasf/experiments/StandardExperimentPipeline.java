package tasf.experiments;

import tasf.config.Config_Simulacion;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * - Selecciona fecha(s) de envíos (rango, fecha fija, día máximo, o índice)
 * - Ejecuta un algoritmo (ALNS o ACO)
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
    private final LocalDate fechaEnviosFiltro;
    private final boolean usarDiaMaximoEnvios;
    private final int fechaEnviosDia;
    private final int duracionEnvios;
    private final LocalDate fechaEnviosRangoInicio;
    private final LocalDate fechaEnviosRangoFin;
    private final List<AlgorithmSpec> algoritmos;

    // Tracking fields for JSON log
    private String tipoSeleccionFecha = "";
    private String fechaSeleccionadaDisplay = "";
    private Map<String, Object> escaneoInfo = new HashMap<>();
    private Map<String, Object> configAdaptativa = new HashMap<>();
    private List<Map<String, Object>> diagnosticoFueraPlazo = new ArrayList<>();

    public StandardExperimentPipeline(
            Path dataDir,
            LocalDate fechaInicioVuelos,
            int diasVuelos,
            int maxEnviosPorArchivo,
            LocalDate fechaEnviosFiltro,
            boolean usarDiaMaximoEnvios,
            int fechaEnviosDia,
            int duracionEnvios,
            LocalDate fechaEnviosRangoInicio,
            LocalDate fechaEnviosRangoFin,
            List<AlgorithmSpec> algoritmos
    ) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir no puede ser null");
        this.fechaInicioVuelos = Objects.requireNonNull(fechaInicioVuelos, "fechaInicioVuelos no puede ser null");
        this.diasVuelos = diasVuelos;
        this.maxEnviosPorArchivo = Math.max(0, maxEnviosPorArchivo);
        this.fechaEnviosFiltro = fechaEnviosFiltro;
        this.usarDiaMaximoEnvios = usarDiaMaximoEnvios;
        this.fechaEnviosDia = fechaEnviosDia;
        this.duracionEnvios = duracionEnvios;
        this.fechaEnviosRangoInicio = fechaEnviosRangoInicio;
        this.fechaEnviosRangoFin = fechaEnviosRangoFin;
        if (algoritmos == null || algoritmos.isEmpty()) {
            throw new IllegalArgumentException("Debe haber al menos un algoritmo");
        }
        this.algoritmos = List.copyOf(algoritmos);
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

Map<LocalDate, Integer> conteoPorDia = new HashMap<>();
        Set<LocalDate> fechasNecesarias = new HashSet<>();
        long msScan;
        LocalDate fechaReferencia = null;
        if (fechaEnviosRangoInicio != null && fechaEnviosRangoFin != null) {
            // Rango de fechas explícito con fechas reales
            tipoSeleccionFecha = "rango";
            fechaSeleccionadaDisplay = fechaEnviosRangoInicio + " a " + fechaEnviosRangoFin;
            System.out.println(String.format("Envíos: %s a %s (%d días)",
                    fechaEnviosRangoInicio, fechaEnviosRangoFin, duracionEnvios));
            LocalDate current = fechaEnviosRangoInicio;
            while (!current.isAfter(fechaEnviosRangoFin)) {
                fechasNecesarias.add(current);
                current = current.plusDays(1);
            }
            fechaReferencia = fechaEnviosRangoInicio;
            conteoPorDia = new HashMap<>();
            msScan = 0;
        } else if (fechaEnviosFiltro != null) {
            // Fecha específica
            tipoSeleccionFecha = "fecha_fija";
            fechaSeleccionadaDisplay = fechaEnviosFiltro.toString();
            fechaReferencia = fechaEnviosFiltro;
            fechasNecesarias.add(fechaReferencia);
            System.out.println(String.format("Envíos: %s", fechaReferencia));
            conteoPorDia = new HashMap<>();
            msScan = 0;
        } else if (usarDiaMaximoEnvios) {
            // Día con más envíos: escanear para encontrarlo
            tipoSeleccionFecha = "dia_maximo";
            conteoPorDia = DatasetTextoLoader.escanearConteoPorDia(
                    resolverCarpetaEnvios(dataDir), aeropuertos
            );
            msScan = (System.nanoTime() - tPipeline) / 1_000_000;
            fechaReferencia = conteoPorDia.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElseThrow(() -> new IllegalStateException("No hay días con envíos"));
            fechaSeleccionadaDisplay = fechaReferencia + " (" + conteoPorDia.get(fechaReferencia) + " envíos)";
            fechasNecesarias.add(fechaReferencia);
            escaneoInfo.put("diasEscaneados", conteoPorDia.size());
            escaneoInfo.put("tiempoEscaneoMs", msScan);
            escaneoInfo.put("totalEnviosEscaneados", conteoPorDia.values().stream().mapToInt(Integer::intValue).sum());
            System.out.println(String.format(Locale.ROOT,
                    "Envíos: %s (%d envíos, escaneo %d días en %dms)",
                    fechaReferencia,
                    conteoPorDia.get(fechaReferencia),
                    conteoPorDia.size(),
                    msScan));
        } else if (fechaEnviosDia > 0 && duracionEnvios > 0) {
            // Rango de fechas por índice de día (relativo a fechaInicioVuelos)
            tipoSeleccionFecha = "rango_indice";
            LocalDate fechaInicioBase = fechaInicioVuelos;
            fechaReferencia = fechaInicioBase.plusDays(fechaEnviosDia - 1);
            LocalDate fechaFin = fechaReferencia.plusDays(duracionEnvios - 1);
            fechaSeleccionadaDisplay = "días " + fechaEnviosDia + "-" + (fechaEnviosDia + duracionEnvios - 1) + " (" + fechaReferencia + " a " + fechaFin + ")";
            if (duracionEnvios == 1) {
                System.out.println(String.format("Envíos: día %d (%s)", fechaEnviosDia, fechaReferencia));
            } else {
                System.out.println(String.format("Envíos: días %d-%d (%s a %s)",
                        fechaEnviosDia, fechaEnviosDia + duracionEnvios - 1, fechaReferencia, fechaFin));
            }
            for (int i = 0; i < duracionEnvios; i++) {
                fechasNecesarias.add(fechaReferencia.plusDays(i));
            }
            conteoPorDia = new HashMap<>();
            msScan = 0;
        } else if (fechaEnviosDia > 0) {
            // Solo un día por índice
            tipoSeleccionFecha = "dia_indice";
            conteoPorDia = new HashMap<>();
            msScan = 0;
            LocalDate fechaInicioBase = fechaInicioVuelos;
            fechaReferencia = fechaInicioBase.plusDays(fechaEnviosDia - 1);
            fechaSeleccionadaDisplay = "día " + fechaEnviosDia + " (" + fechaReferencia + ")";
            System.out.println(String.format("Envíos: día %d (%s)", fechaEnviosDia, fechaReferencia));
        } else {
            tipoSeleccionFecha = "dia_maximo";
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
        List<Integer> nivelesObjetivo;

        if (fechaEnviosRangoInicio != null && fechaEnviosRangoFin != null) {
            // Rango explícito: escanear para obtener conteo real
            conteoPorDia = DatasetTextoLoader.escanearConteoPorDia(
                    resolverCarpetaEnvios(dataDir), aeropuertos
            );
            int conteoTotal = 0;
            for (LocalDate f : fechasNecesarias) {
                conteoTotal += conteoPorDia.getOrDefault(f, 0);
            }
            nivelesObjetivo = List.of(Math.max(1, conteoTotal));
        } else if (fechaEnviosFiltro != null) {
            // Fecha específica: escanear para obtener conteo real
            conteoPorDia = DatasetTextoLoader.escanearConteoPorDia(
                    resolverCarpetaEnvios(dataDir), aeropuertos
            );
            int conteo = conteoPorDia.getOrDefault(fechaEnviosFiltro, 0);
            if (conteo == 0) {
                throw new IllegalArgumentException("No hay envíos para la fecha: " + fechaEnviosFiltro);
            }
            nivelesObjetivo = List.of(conteo);
        } else if (usarDiaMaximoEnvios) {
            // Día máximo: ya encontrado en Paso 1
            int conteo = conteoPorDia.getOrDefault(fechaReferencia, 0);
            nivelesObjetivo = List.of(conteo);
        } else if (fechaEnviosDia > 0 && duracionEnvios > 0) {
            // Rango por índice: escanear para obtener conteo real
            conteoPorDia = DatasetTextoLoader.escanearConteoPorDia(
                    resolverCarpetaEnvios(dataDir), aeropuertos
            );
            int conteoTotal = 0;
            for (LocalDate f : fechasNecesarias) {
                conteoTotal += conteoPorDia.getOrDefault(f, 0);
            }
            nivelesObjetivo = List.of(Math.max(1, conteoTotal));
        } else if (fechaEnviosDia > 0) {
            // Día único por índice: escanear para obtener conteo real
            conteoPorDia = DatasetTextoLoader.escanearConteoPorDia(
                    resolverCarpetaEnvios(dataDir), aeropuertos
            );
            int conteo = conteoPorDia.getOrDefault(fechaReferencia, 0);
            nivelesObjetivo = List.of(Math.max(1, conteo));
        } else {
            // Inalcanzable: Main siempre activa usarDiaMaximoEnvios=true como fallback
            throw new IllegalStateException("Debe especificarse una fecha, rango, índice o usar día máximo");
        }

        // Paso 3: Determinar ventana efectiva de vuelos centrada en las fechas de envio
        LocalDate fechaInicioEfectiva = fechaInicioVuelos;
        int diasVuelosEfectivos = diasVuelos;
        LocalDate ventanaFin = null;

        if (diasVuelos > 0 && !fechasNecesarias.isEmpty()) {
            LocalDate maxFecha = fechasNecesarias.stream().max(LocalDate::compareTo).orElseThrow();
            LocalDate minFecha = fechasNecesarias.stream().min(LocalDate::compareTo).orElseThrow();

            if (fechaEnviosRangoInicio != null) {
                // Rango explícito: ventana desde minFecha - 2 hasta maxFecha + 3
                long diasRango = ChronoUnit.DAYS.between(minFecha, maxFecha) + 1;
                fechaInicioEfectiva = minFecha.minusDays(2);
                diasVuelosEfectivos = (int) Math.max(diasVuelos, diasRango + 5);
            } else if (fechaEnviosFiltro != null) {
                // Fecha específica: ventana desde fecha - 2 hasta fecha + 3
                fechaInicioEfectiva = fechaEnviosFiltro.minusDays(2);
                diasVuelosEfectivos = Math.max(diasVuelos, 6);
            } else if (usarDiaMaximoEnvios) {
                // Día máximo: ventana desde fecha - 2 hasta fecha + 3
                fechaInicioEfectiva = fechaReferencia.minusDays(2);
                diasVuelosEfectivos = Math.max(diasVuelos, 6);
            } else if (fechaEnviosDia > 0) {
                // Rango por índice: ventana desde minFecha - 2 hasta maxFecha + 3
                long diasRango = ChronoUnit.DAYS.between(minFecha, maxFecha) + 1;
                fechaInicioEfectiva = minFecha.minusDays(2);
                diasVuelosEfectivos = (int) Math.max(diasVuelos, diasRango + 5);
            } else {
                // Comportamiento original para múltiples fechas
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
        } else if (!fechasNecesarias.isEmpty() && diasVuelos == 0) {
            // si diasVuelos no especificado (0) pero hay fechas,
            // usar default de 3 días con margen
            LocalDate maxFecha = fechasNecesarias.stream().max(LocalDate::compareTo).orElseThrow();
            fechaInicioEfectiva = maxFecha.minusDays(2);
            diasVuelosEfectivos = 3 + 2;
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

        DistribucionEnviosPorDia.DiaSeleccionado diaUnico = null;
        if (fechaEnviosRangoInicio != null && fechaEnviosRangoFin != null) {
            // Rango explícito con fechas reales: usar TODOS los paquetes del rango completo
            List<Paquete> paquetesRango = new ArrayList<>();
            for (LocalDate f : fechasNecesarias) {
                paquetesRango.addAll(distribucionEnvios.obtenerEnviosDia(f));
            }
            if (paquetesRango.isEmpty()) {
                throw new IllegalArgumentException("No hay envíos para el rango: " + fechaEnviosRangoInicio + " a " + fechaEnviosRangoFin);
            }
            diaUnico = new DistribucionEnviosPorDia.DiaSeleccionado(
                    fechaEnviosRangoInicio,
                    paquetesRango,
                    paquetesRango.size(),
                    0
            );
        } else if (fechaEnviosFiltro != null) {
            // Fecha específica: usar TODOS los paquetes de esa fecha
            List<Paquete> paquetesFecha = distribucionEnvios.obtenerEnviosDia(fechaEnviosFiltro);
            if (paquetesFecha.isEmpty()) {
                throw new IllegalArgumentException("No hay envíos para la fecha: " + fechaEnviosFiltro);
            }
            diaUnico = new DistribucionEnviosPorDia.DiaSeleccionado(
                    fechaEnviosFiltro,
                    paquetesFecha,
                    paquetesFecha.size(),
                    0
            );
        } else if (usarDiaMaximoEnvios) {
            // Día máximo: usar TODOS los paquetes de ese día
            List<Paquete> paquetesDia = distribucionEnvios.obtenerEnviosDia(fechaReferencia);
            if (paquetesDia.isEmpty()) {
                throw new IllegalArgumentException("No hay envíos para el día máximo: " + fechaReferencia);
            }
            diaUnico = new DistribucionEnviosPorDia.DiaSeleccionado(
                    fechaReferencia,
                    paquetesDia,
                    paquetesDia.size(),
                    0
            );
        } else if (fechaEnviosDia > 0 && duracionEnvios > 0) {
            // Rango por índice: usar TODOS los paquetes del rango completo
            List<Paquete> paquetesRango = new ArrayList<>();
            for (LocalDate f : fechasNecesarias) {
                paquetesRango.addAll(distribucionEnvios.obtenerEnviosDia(f));
            }
            if (paquetesRango.isEmpty()) {
                throw new IllegalArgumentException("No hay envíos para el rango de días " + fechaEnviosDia + "-" + (fechaEnviosDia + duracionEnvios - 1));
            }
            diaUnico = new DistribucionEnviosPorDia.DiaSeleccionado(
                    fechaReferencia,
                    paquetesRango,
                    paquetesRango.size(),
                    0
            );
        } else if (fechaEnviosDia > 0) {
            // Día único por índice
            List<Paquete> paquetesDia = distribucionEnvios.obtenerEnviosDia(fechaReferencia);
            if (paquetesDia.isEmpty()) {
                throw new IllegalArgumentException("No hay envíos para el día " + fechaEnviosDia + " (" + fechaReferencia + ")");
            }
            diaUnico = new DistribucionEnviosPorDia.DiaSeleccionado(
                    fechaReferencia,
                    paquetesDia,
                    paquetesDia.size(),
                    0
            );
        } else if (fechaReferencia != null) {
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

        for (int nivelEnvios : nivelesObjetivo) {
            DistribucionEnviosPorDia.DiaSeleccionado dia = diaUnico != null
                    ? diaUnico
                    : distribucionEnvios.encontrarDiaMasCercano(nivelEnvios);
            List<Paquete> paquetesTrabajo = dia.envios;
            Dataset datasetDia = construirDatasetDia(dataset, paquetesTrabajo);
            int totalMaletasDia = paquetesTrabajo.stream().mapToInt(Paquete::getCantidad).sum();

            for (AlgorithmSpec algoritmo : algoritmos) {
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

                if (maletasFueraDePlazo > 0) {
                    System.out.println("  [DIAG] FUERA DE PLAZO detected: " + maletasFueraDePlazo + " maletas");
                    diagnosticarPaquetes(datasetDia, solucion, config);
                }

                rawRecords.add(new RunRecord(
                        algoritmo.name,
                        totalMaletasDia,
                        dia.fecha,
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

                System.out.println(String.format(Locale.ROOT,
                         "  [%s] asignados=%d/%d fuera_plazo=%d colapso=%s costo=%.0f [%dms]",
                         algoritmo.name,
                         asignados, totalEnvios,
                         maletasFueraDePlazo,
                         colapso,
                         solucion.getCostoTotal(),
                         duracionMs));

            }

        }

        ResultTable rawTable = new ResultTable(rawRecords);

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

        return new PipelineResult(
                totalPaquetes, totalMaletas, maletasAsignadas, pedidosAsignados,
                sinAsignar, fueraDePlazo, hayColapso, costoTotal, duracionMs);
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

                Map<String, Object> diagEntry = new LinkedHashMap<>();
                diagEntry.put("pedidoId", p.getId());
                diagEntry.put("origen", p.getOrigenOACI());
                diagEntry.put("destino", p.getDestinoOACI());
                diagEntry.put("cantidad", p.getCantidad());
                diagEntry.put("escalas", ruta.getCantidadSaltos());
                diagEntry.put("creacion", creacion.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
                diagEntry.put("deadline", deadline.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
                diagEntry.put("llegada", llegada.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
                diagEntry.put("retrasoMinutos", retrasoMin);
                diagEntry.put("tipoPlazo", mismoContinente ? "MISMO_CONTINENTE(24h)" : "INTERCONTINENTAL(48h)");
                
                List<Map<String, String>> vuelosRuta = new ArrayList<>();
                for (var v : ruta.getVuelos()) {
                    Map<String, String> vueloEntry = new LinkedHashMap<>();
                    vueloEntry.put("vueloId", v.getId());
                    vueloEntry.put("salida", v.getSalidaUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
                    vueloEntry.put("llegada", v.getLlegadaUtc().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
                    vuelosRuta.add(vueloEntry);
                }
                diagEntry.put("ruta", vuelosRuta);
                diagnosticoFueraPlazo.add(diagEntry);
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

    private Config_Simulacion construirConfig(int totalPaquetes) {
        Config_Simulacion config = new Config_Simulacion();
        config.setPlazoMismoContinente(Duration.ofHours(24));
        config.setPlazoIntercontinental(Duration.ofHours(48));
        config.setMinimaConexion(Duration.ofMinutes(30));

            config.setIteracionesALNS(20);
            config.setIteracionesACO(10);
            config.setHormigasACO(4);
            config.setMaxRutasPorPaquete(4);
            config.setTopRutasACO(2);
            config.setHormigasEliteACO(1);
            config.setMaxEscalas(2);
            config.setVentanaActualizacionPesos(5);
            config.setEvaporacionFeromona(0.4);

        int diasDisponibles = diasVuelos > 0 ? diasVuelos : 1095;
        int maxEscalas = diasDisponibles <= 3 ? 2 : 3;
        config.setMaxEscalas(maxEscalas);

        // Horizonte limitado por deadline máximo (48h intercontinental) + 24h buffer
        config.setHorizonteBusqueda(Duration.ofHours(72));

        boolean muchosPaquetes = totalPaquetes > 5000;
        boolean muchosVuelos = diasVuelos >= 100;

        configAdaptativa.put("iteracionesALNS", config.getIteracionesALNS());
        configAdaptativa.put("iteracionesACO", config.getIteracionesACO());
        configAdaptativa.put("hormigasACO", config.getHormigasACO());
        configAdaptativa.put("maxRutasPorPaquete", config.getMaxRutasPorPaquete());
        configAdaptativa.put("maxEscalas", config.getMaxEscalas());
        configAdaptativa.put("horizonteBusquedaHoras", config.getHorizonteBusqueda().toHours());
        configAdaptativa.put("evaporacionFeromona", config.getEvaporacionFeromona());

        if (muchosPaquetes && muchosVuelos) {
            config.setMaxRutasPorPaquete(100);
            config.setIteracionesALNS(100);
            config.setIteracionesACO(50);
            config.setVentanaActualizacionPesos(5);
            config.setHormigasACO(16);
            config.setAlphaACO(0.8);
            config.setBetaACO(2.8);
            config.setEvaporacionFeromona(0.3);
            config.setPorcentajeRuptura(0.10);
            configAdaptativa.put("iteracionesALNS", 100);
            configAdaptativa.put("iteracionesACO", 50);
            configAdaptativa.put("hormigasACO", 16);
            configAdaptativa.put("maxRutasPorPaquete", 100);
            configAdaptativa.put("alphaACO", 0.8);
            configAdaptativa.put("betaACO", 2.8);
            configAdaptativa.put("evaporacionFeromona", 0.3);
            configAdaptativa.put("porcentajeRuptura", 0.10);
            configAdaptativa.put("modo", "muchos_paquetes_y_vuelos");
            System.out.println(String.format(Locale.ROOT,
                    "  [ADAPTATIVO] paquetes=%d vuelos=%d → ALNS=100, ACO=50, maxRutas=100, ruptura=10%%",
                    totalPaquetes, diasVuelos * 2866));
        } else if (muchosPaquetes) {
            config.setMaxRutasPorPaquete(100);
            config.setIteracionesALNS(100);
            config.setIteracionesACO(50);
            config.setVentanaActualizacionPesos(5);
            config.setPorcentajeRuptura(0.10);
            configAdaptativa.put("iteracionesALNS", 100);
            configAdaptativa.put("iteracionesACO", 50);
            configAdaptativa.put("maxRutasPorPaquete", 100);
            configAdaptativa.put("porcentajeRuptura", 0.10);
            configAdaptativa.put("modo", "muchos_paquetes");
            System.out.println(String.format(Locale.ROOT,
                    "  [ADAPTATIVO] paquetes=%d → ALNS=100, ACO=50, maxRutas=100, ruptura=10%%",
                    totalPaquetes));
        } else {
            configAdaptativa.put("modo", "default");
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
        public final LocalDate fechaSeleccionada;
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
                LocalDate fechaSeleccionada,
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
            this.fechaSeleccionada = fechaSeleccionada;
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

    public static final class ResultTable {
        private final List<RunRecord> rows;

        private ResultTable(List<RunRecord> rows) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }

        public ResultTable resumir() {
            Map<String, List<RunRecord>> grouped = new LinkedHashMap<>();
            for (RunRecord row : rows) {
                String key = row.algoritmo + "|" + row.nivelCargaMaletas;
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }

            List<Map<String, Object>> summaryRows = new ArrayList<>();
            for (List<RunRecord> group : grouped.values()) {
                RunRecord base = group.get(0);
                int corridas = group.size();
                double promedioExito = group.stream().mapToDouble(r -> r.porcentajeExito).average().orElse(0.0);
                double tasaColapso = group.stream().filter(r -> r.hayColapso).count() / (double) corridas;
                double promedioCosto = group.stream().mapToDouble(r -> r.costoTotal).average().orElse(0.0);
                double promedioDuracion = group.stream().mapToLong(r -> r.duracionMs).average().orElse(0.0);
                double promedioNoAsignados = group.stream().mapToInt(r -> r.noAsignados).average().orElse(0.0);
                double promedioFueraPlazo = group.stream().mapToInt(r -> r.maletasFueraDePlazo).average().orElse(0.0);
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("algoritmo", base.algoritmo);
                summary.put("nivelCargaMaletas", base.nivelCargaMaletas);
                summary.put("corridas", corridas);
                summary.put("promedioExito", promedioExito);
                summary.put("tasaColapso", tasaColapso);
                summary.put("promedioCosto", promedioCosto);
                summary.put("promedioDuracionMs", promedioDuracion);
                summary.put("promedioNoAsignados", promedioNoAsignados);
                summary.put("promedioFueraPlazo", promedioFueraPlazo);
                summaryRows.add(summary);
            }
            summaryRows.sort((a, b) -> {
                int cmp = Integer.compare((int)a.get("nivelCargaMaletas"), (int)b.get("nivelCargaMaletas"));
                if (cmp != 0) return cmp;
                return ((String)a.get("algoritmo")).compareTo((String)b.get("algoritmo"));
            });
            return new ResultTable(new ArrayList<>());
        }
    }

    public static final class PipelineResult {
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
        json.append("    \"tipoSeleccionFecha\": \"").append(tipoSeleccionFecha).append("\",\n");
        json.append("    \"fechaSeleccionada\": \"").append(fechaSeleccionadaDisplay).append("\",\n");
        json.append("    \"maletasTotales\": ").append(totalMaletas).append(",\n");
        json.append("    \"maletasAsignadas\": ").append(maletasAsignadas).append(",\n");
        json.append("    \"pedidosTotales\": ").append(totalPedidos).append(",\n");
        json.append("    \"pedidosAsignados\": ").append(pedidosAsignados).append(",\n");
        json.append("    \"rangoDiasVuelos\": \"").append(fechaInicioVuelos).append(" a ").append(fechaFinVuelos).append("\",\n");
        json.append("    \"hayColapso\": ").append(hayColapso).append(",\n");
        json.append("    \"maletasFueraDePlazo\": ").append(maletasFueraPlazo).append(",\n");
        json.append("    \"pedidosSinAsignar\": ").append(noAsignados).append(",\n");
        json.append("    \"costoTotal\": ").append(String.format(Locale.ROOT, "%.2f", costoTotal)).append(",\n");
        json.append("    \"duracionMs\": ").append(duracionMs).append(",\n");
        json.append("    \"generado\": \"").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\"\n");
        json.append("  },\n");

        if (!escaneoInfo.isEmpty()) {
            json.append("  \"escaneo\": {\n");
            json.append("    \"diasEscaneados\": ").append(escaneoInfo.get("diasEscaneados")).append(",\n");
            json.append("    \"tiempoEscaneoMs\": ").append(escaneoInfo.get("tiempoEscaneoMs")).append(",\n");
            json.append("    \"totalEnviosEscaneados\": ").append(escaneoInfo.get("totalEnviosEscaneados")).append("\n");
            json.append("  },\n");
        }

        if (!configAdaptativa.isEmpty()) {
            json.append("  \"configuracion\": {\n");
            json.append("    \"modo\": \"").append(configAdaptativa.get("modo")).append("\",\n");
            json.append("    \"iteracionesALNS\": ").append(configAdaptativa.get("iteracionesALNS")).append(",\n");
            json.append("    \"iteracionesACO\": ").append(configAdaptativa.get("iteracionesACO")).append(",\n");
            json.append("    \"hormigasACO\": ").append(configAdaptativa.get("hormigasACO")).append(",\n");
            json.append("    \"maxRutasPorPaquete\": ").append(configAdaptativa.get("maxRutasPorPaquete")).append(",\n");
            json.append("    \"maxEscalas\": ").append(configAdaptativa.get("maxEscalas")).append(",\n");
            json.append("    \"horizonteBusquedaHoras\": ").append(configAdaptativa.get("horizonteBusquedaHoras")).append(",\n");
            json.append("    \"evaporacionFeromona\": ").append(String.format(Locale.ROOT, "%.2f", (double)configAdaptativa.get("evaporacionFeromona"))).append("\n");
            if (configAdaptativa.containsKey("alphaACO")) {
                json.append("    ,\"alphaACO\": ").append(String.format(Locale.ROOT, "%.2f", (double)configAdaptativa.get("alphaACO"))).append(",\n");
                json.append("    \"betaACO\": ").append(String.format(Locale.ROOT, "%.2f", (double)configAdaptativa.get("betaACO"))).append(",\n");
                json.append("    \"porcentajeRuptura\": ").append(String.format(Locale.ROOT, "%.2f", (double)configAdaptativa.get("porcentajeRuptura"))).append("\n");
            }
            json.append("  },\n");
        }

        if (!diagnosticoFueraPlazo.isEmpty()) {
            json.append("  \"diagnosticoFueraDePlazo\": [\n");
            for (int i = 0; i < diagnosticoFueraPlazo.size(); i++) {
                Map<String, Object> entry = diagnosticoFueraPlazo.get(i);
                if (i > 0) json.append(",\n");
                json.append("    {\n");
                json.append("      \"pedidoId\": \"").append(entry.get("pedidoId")).append("\",\n");
                json.append("      \"origen\": \"").append(entry.get("origen")).append("\",\n");
                json.append("      \"destino\": \"").append(entry.get("destino")).append("\",\n");
                json.append("      \"cantidad\": ").append(entry.get("cantidad")).append(",\n");
                json.append("      \"escalas\": ").append(entry.get("escalas")).append(",\n");
                json.append("      \"creacion\": \"").append(entry.get("creacion")).append("\",\n");
                json.append("      \"deadline\": \"").append(entry.get("deadline")).append("\",\n");
                json.append("      \"llegada\": \"").append(entry.get("llegada")).append("\",\n");
                json.append("      \"retrasoMinutos\": ").append(entry.get("retrasoMinutos")).append(",\n");
                json.append("      \"tipoPlazo\": \"").append(entry.get("tipoPlazo")).append("\",\n");
                json.append("      \"ruta\": [\n");
                @SuppressWarnings("unchecked")
                List<Map<String, String>> vuelosRuta = (List<Map<String, String>>) entry.get("ruta");
                for (int j = 0; j < vuelosRuta.size(); j++) {
                    Map<String, String> vuelo = vuelosRuta.get(j);
                    if (j > 0) json.append(",\n");
                    json.append("        {\n");
                    json.append("          \"vueloId\": \"").append(vuelo.get("vueloId")).append("\",\n");
                    json.append("          \"salida\": \"").append(vuelo.get("salida")).append("\",\n");
                    json.append("          \"llegada\": \"").append(vuelo.get("llegada")).append("\"\n");
                    json.append("        }");
                }
                json.append("\n      ]\n");
                json.append("    }");
            }
            json.append("\n  ],\n");
        }

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
