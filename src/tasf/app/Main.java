package tasf.app;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.Solucion;
import tasf.io.DatasetTextoLoader;
import tasf.model.Ruta;
import tasf.strategy.PlanificadorStrategy;
import tasf.strategy.aco.ACO_Strategy;
import tasf.strategy.alns.ALNS_Strategy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final int HORIZONTE_BUSQUEDA_HORAS = 60;

    public static void main(String[] args) {
        ParametrosCli p = ParametrosCli.desdeArgs(args);
        Path outputDir = resolverOutputDir(p.carpetaDatos);

        try (SimulacionLogger logger = SimulacionLogger.crear(outputDir)) {
            long t0Total = System.nanoTime();
            logger.log("Inicio de simulacion");
            logger.log("Data dir: " + p.carpetaDatos);
            logger.log("Output dir: " + outputDir);
            logger.log("Algoritmo: " + p.algoritmo);
            logger.log("Min envios: " + p.minEnvios);
            logger.log("Max envios total: " + p.maxEnviosTotal);
            logger.log("Random seed: " + p.randomSeed);

            System.out.println("========== Simulacion Tasf.B2B ==========");
            System.out.println(DatasetTextoLoader.descripcionEstructuraEsperada(p.carpetaDatos));
            System.out.println("Fecha inicio vuelos: " + p.fechaInicioVuelos);
                System.out.println(
                    "Dias de vuelos solicitados: "
                        + (p.diasVuelos <= 0 ? "AUTO(dias-simulacion+1)" : p.diasVuelos)
                );
            System.out.println("Dias de simulacion: " + p.diasSimulacion);
            System.out.println("Min envios: " + p.minEnvios);
            System.out.println("Max envios total: " + p.maxEnviosTotal);
            System.out.println("Random seed: " + p.randomSeed);
            System.out.println("Algoritmo: " + p.algoritmo);
            System.out.println("Log file: " + logger.getFilePath());

                int diasVuelosEfectivos = p.diasVuelos <= 0 ? p.diasSimulacion + 1 : p.diasVuelos;
            System.out.println("Dias de vuelos efectivos: " + diasVuelosEfectivos);
                logger.log("Dias de vuelos efectivos: " + diasVuelosEfectivos);

            Dataset datos;
            DatasetTextoLoader.ResultadoCargaSimulacion carga;
            long t0Carga = System.nanoTime();
            try {
                carga = DatasetTextoLoader.cargarDatasetSimulacion(
                        p.carpetaDatos,
                        p.fechaInicioVuelos,
                        diasVuelosEfectivos,
                        p.diasSimulacion,
                        p.minEnvios,
                        p.maxEnviosTotal,
                        p.randomSeed
                );
                datos = carga.dataset();
            } catch (IOException | RuntimeException e) {
                logger.log("ERROR cargando dataset: " + e.getMessage());
                System.err.println("Error cargando dataset: " + e.getMessage());
                System.exit(1);
                return;
            }

            long msCarga = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0Carga);
            System.out.println("Aeropuertos cargados: " + datos.getAeropuertos().size());
            System.out.println("Vuelos cargados: " + datos.getVuelos().size());
            System.out.println("Paquetes cargados: " + datos.getPaquetes().size());
            logger.log(
                    "Dataset cargado en " + msCarga + " ms"
                            + " | aeropuertos=" + datos.getAeropuertos().size()
                            + " | vuelos=" + datos.getVuelos().size()
                            + " | paquetes=" + datos.getPaquetes().size()
                            + " | totalCargados=" + carga.totalCargados()
                            + " | totalFiltrados=" + carga.totalFiltrados()
                            + " | totalUsados=" + carga.totalUsados()
            );

            Config_Simulacion config = construirConfig(p);
            LocalDate inicioSim = carga.inicioSimulacion();
            LocalDate finSim = carga.finSimulacion();

            if (carga.ventanaDescartada()) {
                logger.log(
                        "Ventana descartada | fecha_inicio=" + inicioSim
                                + " | total_filtrados=" + carga.totalFiltrados()
                                + " | total_usados=" + carga.totalUsados()
                                + " | motivo=" + carga.motivo()
                );
                System.out.println("ventana_descartada=true");
                System.out.println("motivo=" + carga.motivo());
                System.exit(0);
                return;
            }

            System.out.println(
                    "Ventana simulacion: " + inicioSim + ".." + finSim
                            + " | filtrados=" + carga.totalFiltrados()
                            + " | usados=" + carga.totalUsados()
            );
            logger.log(
                    "Ventana simulacion aceptada | fecha_inicio=" + inicioSim
                            + " | fecha_fin=" + finSim
                            + " | total_filtrados=" + carga.totalFiltrados()
                            + " | total_usados=" + carga.totalUsados()
            );

            LocalDateTime finSimulacionUtcExclusivo = finSim.plusDays(1).atStartOfDay();
            config.setFinSimulacionUtcExclusivo(finSimulacionUtcExclusivo);
            logger.log("Corte estricto de simulacion (UTC exclusivo): " + finSimulacionUtcExclusivo);
            System.out.println("Corte estricto de simulacion (UTC exclusivo): " + finSimulacionUtcExclusivo);

            if (p.algoritmo.equals("AMBOS")) {
                ejecutarAmbosEnParalelo(datos, config, logger, inicioSim, carga.totalFiltrados(), carga.totalUsados());
            } else if (p.algoritmo.equals("ALNS")) {
                PlanificadorStrategy alns = new ALNS_Strategy(17L);
                Solucion solucion = ejecutarEstrategia("ALNS", alns, datos, config, logger, inicioSim, carga.totalFiltrados(), carga.totalUsados());
                imprimirResumen(solucion, datos);
            } else if (p.algoritmo.equals("ACO")) {
                PlanificadorStrategy aco = new ACO_Strategy(17L);
                Solucion solucion = ejecutarEstrategia("ACO", aco, datos, config, logger, inicioSim, carga.totalFiltrados(), carga.totalUsados());
                imprimirResumen(solucion, datos);
            }

            long msTotal = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0Total);
            logger.log("Simulacion finalizada | duracionTotalMs=" + msTotal);
        } catch (IOException e) {
            System.err.println("No se pudo crear/escribir log de simulacion: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void ejecutarAmbosEnParalelo(
            Dataset datos,
            Config_Simulacion config,
            SimulacionLogger logger,
            LocalDate fechaInicio,
            int totalFiltrados,
            int totalUsados
    ) {
        logger.log("Ejecucion paralela iniciada para ALNS y ACO");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Solucion> alnsF = CompletableFuture.supplyAsync(
                () -> ejecutarEstrategia("ALNS", new ALNS_Strategy(17L), datos, config, logger, fechaInicio, totalFiltrados, totalUsados),
                    executor
            );
            CompletableFuture<Solucion> acoF = CompletableFuture.supplyAsync(
                () -> ejecutarEstrategia("ACO", new ACO_Strategy(17L), datos, config, logger, fechaInicio, totalFiltrados, totalUsados),
                    executor
            );

            Solucion solucionAlns = alnsF.join();
            Solucion solucionAco = acoF.join();

            imprimirResumen(solucionAlns, datos);
            imprimirResumen(solucionAco, datos);
            logger.log("Ejecucion paralela completada");
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.log("ERROR en ejecucion paralela: " + cause.getMessage());
            throw new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Solucion ejecutarEstrategia(
            String nombre,
            PlanificadorStrategy estrategia,
            Dataset datos,
            Config_Simulacion config,
            SimulacionLogger logger,
            LocalDate fechaInicio,
            int totalFiltrados,
            int totalUsados
    ) {
        long t0 = System.nanoTime();
        logger.log(
            "Corrida | fecha_inicio=" + fechaInicio
                + " | total_filtrados=" + totalFiltrados
                + " | total_usados=" + totalUsados
                + " | algoritmo=" + nombre
        );
        logger.log("Inicio estrategia " + nombre);
        Solucion solucion = estrategia.planificar(datos, config);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        logger.log(
                "Fin estrategia " + nombre
                        + " | ms=" + ms
                        + " | noAsignados=" + solucion.getPaquetesNoAsignados().size()
                        + " | fueraPlazo=" + solucion.getMaletasFueraDePlazo()
                        + " | colapso=" + solucion.getEventosColapso()
                        + " | costo=" + String.format(Locale.ROOT, "%.2f", solucion.getCostoTotal())
        );
        logAsignaciones(logger, nombre, solucion);
        return solucion;
    }

    private static Path resolverOutputDir(Path dataDir) {
        Path base = dataDir.toAbsolutePath().normalize();
        if (base.getFileName() != null && base.getFileName().toString().equalsIgnoreCase("input")) {
            Path parent = base.getParent();
            if (parent != null) {
                return parent.resolve("output");
            }
        }
        return base.resolve("output");
    }

    private static Config_Simulacion construirConfig(ParametrosCli p) {
        Config_Simulacion config = new Config_Simulacion();
        config.setPlazoMismoContinente(Duration.ofHours(24));
        config.setPlazoIntercontinental(Duration.ofHours(48));
        config.setMinimaConexion(Duration.ofMinutes(30));
        config.setHorizonteBusqueda(Duration.ofHours(HORIZONTE_BUSQUEDA_HORAS));

        // Configuracion ajustada para dataset grande y soluciones parciales rapidas.
        config.setIteracionesALNS(p.iteracionesAlns);
        config.setIteracionesACO(p.iteracionesAco);
        config.setHormigasACO(p.hormigasAco);
        config.setTopRutasACO(4);
        config.setHormigasEliteACO(Math.max(2, p.hormigasAco / 3));
        config.setFactorEliteACO(1.6);
        config.setFactorGlobalBestACO(2.6);
        config.setAlphaACO(0.9);
        config.setBetaACO(3.2);
        config.setMaxEscalas(3);
        config.setMaxRutasPorPaquete(6);
        config.setVentanaActualizacionPesos(10);
        return config;
    }

    private static void imprimirResumen(Solucion solucion, Dataset datos) {
        int total = datos.getPaquetes().size();
        int noAsignados = solucion.getPaquetesNoAsignados().size();
        int asignados = total - noAsignados;

        System.out.println("--------------------------------------------");
        System.out.println("Estrategia: " + solucion.getEstrategia());
        System.out.println("Asignados: " + asignados + " / " + total);
        System.out.println("Fuera de plazo: " + solucion.getMaletasFueraDePlazo());
        System.out.println("Eventos de colapso: " + solucion.getEventosColapso());
        System.out.printf("Horas promedio de entrega: %.2f%n", solucion.getHorasPromedioEntrega());
        System.out.printf("Costo total: %.2f%n", solucion.getCostoTotal());
        System.out.println("Metricas: " + solucion.getMetricas());
        System.out.println("Rutas asignadas:");
        imprimirAsignacionesConsola(solucion);
    }

    private static void imprimirAsignacionesConsola(Solucion solucion) {
        ArrayList<Map.Entry<String, Ruta>> asignaciones = new ArrayList<>(solucion.getRutasAsignadas().entrySet());
        asignaciones.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Ruta> entry : asignaciones) {
            System.out.println("  " + entry.getKey() + " -> " + describirRuta(entry.getValue()));
        }
    }

    private static void logAsignaciones(SimulacionLogger logger, String nombre, Solucion solucion) {
        ArrayList<Map.Entry<String, Ruta>> asignaciones = new ArrayList<>(solucion.getRutasAsignadas().entrySet());
        asignaciones.sort(Map.Entry.comparingByKey());
        logger.log("Asignaciones " + nombre + " | total=" + asignaciones.size());
        for (Map.Entry<String, Ruta> entry : asignaciones) {
            logger.log("Asignada " + entry.getKey() + " -> " + describirRuta(entry.getValue()));
        }
    }

    private static String describirRuta(Ruta ruta) {
        return ruta.getVuelos().stream().map(v -> v.getId()).collect(Collectors.joining(" -> "));
    }

    private static class ParametrosCli {
        private final Path carpetaDatos;
        private final LocalDate fechaInicioVuelos;
        private final int diasVuelos;
        private final int maxEnviosPorArchivo;
        private final int minEnvios;
        private final int maxEnviosTotal;
        private final long randomSeed;
        private final int diasSimulacion;
        private final String algoritmo;
        private final int iteracionesAlns;
        private final int iteracionesAco;
        private final int hormigasAco;

        private ParametrosCli(
                Path carpetaDatos,
                LocalDate fechaInicioVuelos,
                int diasVuelos,
                int maxEnviosPorArchivo,
                int minEnvios,
                int maxEnviosTotal,
                long randomSeed,
                int diasSimulacion,
                String algoritmo,
                int iteracionesAlns,
                int iteracionesAco,
                int hormigasAco
        ) {
            this.carpetaDatos = carpetaDatos;
            this.fechaInicioVuelos = fechaInicioVuelos;
            this.diasVuelos = diasVuelos;
            this.maxEnviosPorArchivo = maxEnviosPorArchivo;
            this.minEnvios = minEnvios;
            this.maxEnviosTotal = maxEnviosTotal;
            this.randomSeed = randomSeed;
            this.diasSimulacion = diasSimulacion;
            this.algoritmo = algoritmo;
            this.iteracionesAlns = iteracionesAlns;
            this.iteracionesAco = iteracionesAco;
            this.hormigasAco = hormigasAco;
        }

        private static ParametrosCli desdeArgs(String[] args) {
            Path carpetaDatos = Path.of("data").toAbsolutePath().normalize();
            LocalDate fechaInicio = LocalDate.of(2026, 1, 2);
            int diasVuelos = 0;
            int maxEnviosPorArchivo = 0;
            int minEnvios = 200;
            int maxEnviosTotal = 800;
            long randomSeed = 17L;
            int diasSimulacion = 30;
            String algoritmo = "AMBOS";
            int iteracionesAlns = 30;
            int iteracionesAco = 12;
            int hormigasAco = 6;

            for (String arg : args) {
                if (arg.startsWith("--data-dir=")) {
                    carpetaDatos = Path.of(arg.substring("--data-dir=".length())).toAbsolutePath().normalize();
                } else if (arg.startsWith("--fecha=")) {
                    fechaInicio = LocalDate.parse(arg.substring("--fecha=".length()));
                } else if (arg.startsWith("--dias-vuelos=")) {
                    diasVuelos = Integer.parseInt(arg.substring("--dias-vuelos=".length()));
                } else if (arg.startsWith("--max-envios=")) {
                    String valor = arg.substring("--max-envios=".length()).trim().toLowerCase(Locale.ROOT);
                    if (valor.equals("all") || valor.equals("todo") || valor.equals("todos")) {
                        maxEnviosPorArchivo = 0;
                    } else {
                        maxEnviosPorArchivo = Integer.parseInt(valor);
                    }
                } else if (arg.startsWith("--min-envios=")) {
                    minEnvios = Integer.parseInt(arg.substring("--min-envios=".length()));
                } else if (arg.startsWith("--max-envios-total=")) {
                    maxEnviosTotal = Integer.parseInt(arg.substring("--max-envios-total=".length()));
                } else if (arg.startsWith("--random-seed=")) {
                    randomSeed = Long.parseLong(arg.substring("--random-seed=".length()));
                } else if (arg.startsWith("--dias-simulacion=")) {
                    diasSimulacion = Integer.parseInt(arg.substring("--dias-simulacion=".length()));
                } else if (arg.startsWith("--algoritmo=")) {
                    algoritmo = normalizarAlgoritmo(arg.substring("--algoritmo=".length()));
                } else if (arg.startsWith("--iter-alns=")) {
                    iteracionesAlns = Integer.parseInt(arg.substring("--iter-alns=".length()));
                } else if (arg.startsWith("--iter-aco=")) {
                    iteracionesAco = Integer.parseInt(arg.substring("--iter-aco=".length()));
                } else if (arg.startsWith("--hormigas-aco=")) {
                    hormigasAco = Integer.parseInt(arg.substring("--hormigas-aco=".length()));
                }
            }

            return new ParametrosCli(
                    carpetaDatos,
                    fechaInicio,
                    Math.max(0, diasVuelos),
                    Math.max(0, maxEnviosPorArchivo),
                    Math.max(0, minEnvios),
                    Math.max(0, maxEnviosTotal),
                    randomSeed,
                    Math.max(1, diasSimulacion),
                    algoritmo,
                    Math.max(1, iteracionesAlns),
                    Math.max(1, iteracionesAco),
                    Math.max(1, hormigasAco)
            );
        }

        private static String normalizarAlgoritmo(String raw) {
            String x = raw.trim().toUpperCase(Locale.ROOT);
            if (x.equals("ALNS") || x.equals("ACO") || x.equals("AMBOS")) {
                return x;
            }
            return "AMBOS";
        }
    }
}
