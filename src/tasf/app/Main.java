package tasf.app;

import tasf.experiments.StandardExperimentPipeline;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Punto de entrada unico del proyecto.
 * Ejecuta el pipeline estandar de experimentacion.
 */
public class Main {

    public static void main(String[] args) {
        ParametrosCli parametros = ParametrosCli.desdeArgs(args);

        try {
            StandardExperimentPipeline pipeline = new StandardExperimentPipeline(
                    parametros.dataDir,
                    parametros.fechaInicioVuelos,
                    parametros.diasVuelos,
                    parametros.maxEnviosPorArchivo,
                    parametros.corridasPorAlgoritmo,
                    parametros.fechaEnviosFiltro,
                    parametros.usarDiaMaximoEnvios,
                    parametros.barrerPorcentajeEnvios,
                    parametros.porcentajeEnviosInicial,
                    parametros.porcentajeEnviosMinimo,
                    parametros.pasoPorcentajeEnvios,
                    java.util.List.of(
                            new StandardExperimentPipeline.AlgorithmSpec("ALNS", () -> new tasf.strategy.alns.ALNS_RutasPlanner(parametros.semillaALNS)),
                            new StandardExperimentPipeline.AlgorithmSpec("ACO", () -> new tasf.strategy.aco.ACO_RutasPlanner(parametros.semillaACO))
                    )
            );

            StandardExperimentPipeline.PipelineResult resultado = pipeline.ejecutar();

            System.out.println("=== Pipeline Estandar Ejecutado ===");
            System.out.println("Capacidad maxima diaria: " + resultado.capacidadMaximaDiaria + " maletas");
            System.out.println("Niveles objetivo: " + resultado.nivelesObjetivo);
            System.out.println("CSV crudo: " + resultado.rawCsv);
            System.out.println("CSV resumen: " + resultado.summaryCsv);
            System.out.println("Corridas totales: " + resultado.rawTable.getRows().size());
        } catch (Exception e) {
            System.err.println("Error ejecutando pipeline estandar: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static final class ParametrosCli {
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
        private final long semillaALNS;
        private final long semillaACO;

        private ParametrosCli(
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
                long semillaALNS,
                long semillaACO
        ) {
            this.dataDir = dataDir;
            this.fechaInicioVuelos = fechaInicioVuelos;
            this.diasVuelos = diasVuelos;
            this.maxEnviosPorArchivo = maxEnviosPorArchivo;
            this.corridasPorAlgoritmo = corridasPorAlgoritmo;
            this.fechaEnviosFiltro = fechaEnviosFiltro;
            this.usarDiaMaximoEnvios = usarDiaMaximoEnvios;
            this.barrerPorcentajeEnvios = barrerPorcentajeEnvios;
            this.porcentajeEnviosInicial = porcentajeEnviosInicial;
            this.porcentajeEnviosMinimo = porcentajeEnviosMinimo;
            this.pasoPorcentajeEnvios = pasoPorcentajeEnvios;
            this.semillaALNS = semillaALNS;
            this.semillaACO = semillaACO;
        }

        private static ParametrosCli desdeArgs(String[] args) {
            Path dataDir = Path.of("data").toAbsolutePath().normalize();
            LocalDate fechaInicioVuelos = LocalDate.of(2026, 1, 2);
            int diasVuelos = 0;  // 0 = cargar TODOS los vuelos disponibles (2 años de cobertura)
            int maxEnviosPorArchivo = 0;
            int corridasPorAlgoritmo = 10;
            LocalDate fechaEnviosFiltro = null;
            boolean usarDiaMaximoEnvios = false;
            boolean barrerPorcentajeEnvios = false;
            int porcentajeEnviosInicial = 100;
            int porcentajeEnviosMinimo = 10;
            int pasoPorcentajeEnvios = 5;
            long semillaALNS = 17L;
            long semillaACO = 17L;

            for (String arg : args) {
                if (arg.startsWith("--data-dir=")) {
                    dataDir = Path.of(arg.substring("--data-dir=".length())).toAbsolutePath().normalize();
                } else if (arg.startsWith("--fecha-inicio-vuelos=")) {
                    fechaInicioVuelos = LocalDate.parse(arg.substring("--fecha-inicio-vuelos=".length()));
                } else if (arg.startsWith("--dias-vuelos=")) {
                    diasVuelos = Integer.parseInt(arg.substring("--dias-vuelos=".length()));
                } else if (arg.startsWith("--max-envios=")) {
                    String valor = arg.substring("--max-envios=".length()).trim().toLowerCase();
                    if (valor.equals("todos") || valor.equals("todo") || valor.equals("all")) {
                        maxEnviosPorArchivo = 0;
                    } else {
                        maxEnviosPorArchivo = Integer.parseInt(valor);
                    }
                } else if (arg.startsWith("--corridas=")) {
                    corridasPorAlgoritmo = Integer.parseInt(arg.substring("--corridas=".length()));
                } else if (arg.startsWith("--fecha-envios=")) {
                    String valor = arg.substring("--fecha-envios=".length()).trim().toLowerCase();
                    if (valor.equals("max") || valor.equals("maximo") || valor.equals("máximo")) {
                        usarDiaMaximoEnvios = true;
                        fechaEnviosFiltro = null;
                    } else {
                        fechaEnviosFiltro = LocalDate.parse(valor);
                        usarDiaMaximoEnvios = false;
                    }
                } else if (arg.equals("--barrer-porcentaje-envios")) {
                    barrerPorcentajeEnvios = true;
                } else if (arg.startsWith("--porcentaje-envios-inicial=")) {
                    porcentajeEnviosInicial = Integer.parseInt(arg.substring("--porcentaje-envios-inicial=".length()));
                } else if (arg.startsWith("--porcentaje-envios-minimo=")) {
                    porcentajeEnviosMinimo = Integer.parseInt(arg.substring("--porcentaje-envios-minimo=".length()));
                } else if (arg.startsWith("--paso-porcentaje-envios=")) {
                    pasoPorcentajeEnvios = Integer.parseInt(arg.substring("--paso-porcentaje-envios=".length()));
                } else if (arg.startsWith("--semilla-alns=")) {
                    semillaALNS = Long.parseLong(arg.substring("--semilla-alns=".length()));
                } else if (arg.startsWith("--semilla-aco=")) {
                    semillaACO = Long.parseLong(arg.substring("--semilla-aco=".length()));
                }
            }

            return new ParametrosCli(
                    dataDir,
                    fechaInicioVuelos,
                    diasVuelos,
                    Math.max(0, maxEnviosPorArchivo),
                    Math.max(1, corridasPorAlgoritmo),
                    fechaEnviosFiltro,
                    usarDiaMaximoEnvios,
                    barrerPorcentajeEnvios,
                    porcentajeEnviosInicial,
                    porcentajeEnviosMinimo,
                    pasoPorcentajeEnvios,
                    semillaALNS,
                    semillaACO
            );
        }
    }
}
