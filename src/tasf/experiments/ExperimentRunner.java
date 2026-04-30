package tasf.experiments;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;
import tasf.strategy.PlanificadorRutasStrategy;
import tasf.strategy.flow.MinCostFlowAssigner;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Runner de experimentos para comparar metaheuristicos (ej. ACO y ALNS)
 * usando un mismo asignador deterministico (Min-Cost Flow).
 *
 * Flujo por nivel de carga:
 * 1) Selecciona el dia con carga total de maletas mas cercana al objetivo
 * 2) Filtra dataset para ese dia
 * 3) Ejecuta cada algoritmo N corridas
 * 4) Registra metricas por corrida en forma tabular
 */
public class ExperimentRunner {

    private final Dataset datasetBase;
    private final Config_Simulacion config;
    private final List<AlgorithmSpec> algoritmos;
    private final MinCostFlowAssigner assigner;

    public ExperimentRunner(
            Dataset datasetBase,
            Config_Simulacion config,
            List<AlgorithmSpec> algoritmos
    ) {
        this.datasetBase = Objects.requireNonNull(datasetBase, "datasetBase no puede ser null");
        this.config = Objects.requireNonNull(config, "config no puede ser null");
        if (algoritmos == null || algoritmos.isEmpty()) {
            throw new IllegalArgumentException("Debe registrar al menos un algoritmo");
        }
        this.algoritmos = List.copyOf(algoritmos);
        this.assigner = new MinCostFlowAssigner();
    }

    /**
     * Ejecuta experimentos para cada nivel de carga objetivo.
     *
     * @param nivelesCargaMaletas objetivos de carga (maletas)
     * @param corridasPorAlgoritmo cantidad de corridas por algoritmo y nivel
     * @return tabla con filas de resultados por corrida y resumenes
     */
    public ExperimentTable run(List<Integer> nivelesCargaMaletas, int corridasPorAlgoritmo) {
        if (nivelesCargaMaletas == null || nivelesCargaMaletas.isEmpty()) {
            throw new IllegalArgumentException("nivelesCargaMaletas no puede estar vacio");
        }
        if (corridasPorAlgoritmo < 1) {
            throw new IllegalArgumentException("corridasPorAlgoritmo debe ser >= 1");
        }

        List<RunResult> filas = new ArrayList<>();

        for (int nivelCargaObjetivo : nivelesCargaMaletas) {
            if (nivelCargaObjetivo < 0) {
                throw new IllegalArgumentException("Los niveles de carga no pueden ser negativos");
            }

            DaySelection daySelection = seleccionarDiaParaCarga(nivelCargaObjetivo);
            Dataset dayDataset = construirDatasetDelDia(daySelection.fecha, daySelection.paquetes);

            for (AlgorithmSpec algoritmo : algoritmos) {
                for (int corrida = 1; corrida <= corridasPorAlgoritmo; corrida++) {
                    long t0 = System.nanoTime();

                    PlanificadorRutasStrategy planner = algoritmo.strategyFactory.get();
                    Map<String, List<Ruta>> rutas = planner.planificarRutas(dayDataset, config);
                    Map<String, Vuelo> asignaciones = assigner.asignarEnviosAVuelos(rutas, dayDataset, config);

                    int totalMaletas = calcularTotalMaletas(daySelection.paquetes);
                    int maletasAsignadas = calcularMaletasAsignadas(daySelection.paquetes, asignaciones);
                    int maletasNoAsignadas = totalMaletas - maletasAsignadas;
                    double porcentajeExito = totalMaletas == 0
                            ? 0.0
                            : (100.0 * maletasAsignadas) / totalMaletas;

                    long duracionMs = (System.nanoTime() - t0) / 1_000_000;

                    filas.add(new RunResult(
                            nivelCargaObjetivo,
                            daySelection.fecha,
                            daySelection.cargaMaletasDia,
                            algoritmo.name,
                            corrida,
                            totalMaletas,
                            maletasAsignadas,
                            maletasNoAsignadas,
                            porcentajeExito,
                            duracionMs
                    ));
                }
            }
        }

        return new ExperimentTable(filas);
    }

    private DaySelection seleccionarDiaParaCarga(int cargaObjetivoMaletas) {
        Map<LocalDate, List<Paquete>> paquetesPorDia = datasetBase.getPaquetes().stream()
                .collect(Collectors.groupingBy(Paquete::getFecha));

        if (paquetesPorDia.isEmpty()) {
            throw new IllegalStateException("No hay paquetes en datasetBase");
        }

        DaySelection mejor = null;
        for (Map.Entry<LocalDate, List<Paquete>> entry : paquetesPorDia.entrySet()) {
            int cargaDia = calcularTotalMaletas(entry.getValue());
            int diferencia = Math.abs(cargaDia - cargaObjetivoMaletas);

            if (mejor == null
                    || diferencia < mejor.diferencia
                    || (diferencia == mejor.diferencia && entry.getKey().isBefore(mejor.fecha))) {
                mejor = new DaySelection(entry.getKey(), entry.getValue(), cargaDia, diferencia);
            }
        }

        return mejor;
    }

    private Dataset construirDatasetDelDia(LocalDate fecha, List<Paquete> paquetesDia) {
        List<Paquete> copia = new ArrayList<>(paquetesDia);
        return new Dataset(datasetBase.getAeropuertos(), datasetBase.getVuelos(), copia);
    }

    private int calcularTotalMaletas(List<Paquete> paquetes) {
        return paquetes.stream().mapToInt(Paquete::getCantidad).sum();
    }

    private int calcularMaletasAsignadas(List<Paquete> paquetes, Map<String, Vuelo> asignaciones) {
        int maletas = 0;
        for (Paquete paquete : paquetes) {
            if (asignaciones.containsKey(paquete.getId())) {
                maletas += paquete.getCantidad();
            }
        }
        return maletas;
    }

    public static final class AlgorithmSpec {
        public final String name;
        public final Supplier<PlanificadorRutasStrategy> strategyFactory;

        public AlgorithmSpec(String name, Supplier<PlanificadorRutasStrategy> strategyFactory) {
            this.name = Objects.requireNonNull(name, "name no puede ser null");
            this.strategyFactory = Objects.requireNonNull(strategyFactory, "strategyFactory no puede ser null");
        }
    }

    private static final class DaySelection {
        private final LocalDate fecha;
        private final List<Paquete> paquetes;
        private final int cargaMaletasDia;
        private final int diferencia;

        private DaySelection(LocalDate fecha, List<Paquete> paquetes, int cargaMaletasDia, int diferencia) {
            this.fecha = fecha;
            this.paquetes = paquetes;
            this.cargaMaletasDia = cargaMaletasDia;
            this.diferencia = diferencia;
        }
    }

    public static final class RunResult {
        public final int nivelCargaObjetivoMaletas;
        public final LocalDate fechaSeleccionada;
        public final int cargaRealDiaMaletas;
        public final String algoritmo;
        public final int corrida;
        public final int maletasTotales;
        public final int maletasAsignadas;
        public final int maletasNoAsignadas;
        public final double porcentajeExito;
        public final long duracionMs;

        public RunResult(
                int nivelCargaObjetivoMaletas,
                LocalDate fechaSeleccionada,
                int cargaRealDiaMaletas,
                String algoritmo,
                int corrida,
                int maletasTotales,
                int maletasAsignadas,
                int maletasNoAsignadas,
                double porcentajeExito,
                long duracionMs
        ) {
            this.nivelCargaObjetivoMaletas = nivelCargaObjetivoMaletas;
            this.fechaSeleccionada = fechaSeleccionada;
            this.cargaRealDiaMaletas = cargaRealDiaMaletas;
            this.algoritmo = algoritmo;
            this.corrida = corrida;
            this.maletasTotales = maletasTotales;
            this.maletasAsignadas = maletasAsignadas;
            this.maletasNoAsignadas = maletasNoAsignadas;
            this.porcentajeExito = porcentajeExito;
            this.duracionMs = duracionMs;
        }
    }

    public static final class SummaryRow {
        public final int nivelCargaObjetivoMaletas;
        public final String algoritmo;
        public final int corridas;
        public final double promedioExito;
        public final double minExito;
        public final double maxExito;
        public final double promedioDuracionMs;

        public SummaryRow(
                int nivelCargaObjetivoMaletas,
                String algoritmo,
                int corridas,
                double promedioExito,
                double minExito,
                double maxExito,
                double promedioDuracionMs
        ) {
            this.nivelCargaObjetivoMaletas = nivelCargaObjetivoMaletas;
            this.algoritmo = algoritmo;
            this.corridas = corridas;
            this.promedioExito = promedioExito;
            this.minExito = minExito;
            this.maxExito = maxExito;
            this.promedioDuracionMs = promedioDuracionMs;
        }
    }

    public static final class ExperimentTable {
        private final List<RunResult> rows;

        private ExperimentTable(List<RunResult> rows) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }

        public List<RunResult> getRows() {
            return rows;
        }

        /**
         * Agrega estadisticas por par (nivelCarga, algoritmo).
         */
        public List<SummaryRow> summarizeByLevelAndAlgorithm() {
            Map<String, List<RunResult>> grouped = new LinkedHashMap<>();
            for (RunResult row : rows) {
                String key = row.nivelCargaObjetivoMaletas + "|" + row.algoritmo;
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }

            List<SummaryRow> summary = new ArrayList<>();
            for (List<RunResult> group : grouped.values()) {
                RunResult base = group.get(0);
                int corridas = group.size();

                double promedioExito = group.stream().mapToDouble(r -> r.porcentajeExito).average().orElse(0.0);
                double minExito = group.stream().mapToDouble(r -> r.porcentajeExito).min().orElse(0.0);
                double maxExito = group.stream().mapToDouble(r -> r.porcentajeExito).max().orElse(0.0);
                double promedioDuracion = group.stream().mapToLong(r -> r.duracionMs).average().orElse(0.0);

                summary.add(new SummaryRow(
                        base.nivelCargaObjetivoMaletas,
                        base.algoritmo,
                        corridas,
                        promedioExito,
                        minExito,
                        maxExito,
                        promedioDuracion
                ));
            }

            summary.sort(Comparator
                    .comparingInt((SummaryRow s) -> s.nivelCargaObjetivoMaletas)
                    .thenComparing(s -> s.algoritmo));
            return summary;
        }

        /**
         * Exporta la tabla de corridas a filas CSV (incluye header en la primera linea).
         */
        public List<String> toCsvLines() {
            List<String> lines = new ArrayList<>();
            lines.add("nivelObjetivoMaletas,fechaSeleccionada,cargaRealDiaMaletas,algoritmo,corrida,maletasTotales,maletasAsignadas,maletasNoAsignadas,porcentajeExito,duracionMs");
            for (RunResult row : rows) {
                lines.add(String.format(
                        "%d,%s,%d,%s,%d,%d,%d,%d,%.4f,%d",
                        row.nivelCargaObjetivoMaletas,
                        row.fechaSeleccionada,
                        row.cargaRealDiaMaletas,
                        row.algoritmo,
                        row.corrida,
                        row.maletasTotales,
                        row.maletasAsignadas,
                        row.maletasNoAsignadas,
                        row.porcentajeExito,
                        row.duracionMs
                ));
            }
            return lines;
        }
    }
}
