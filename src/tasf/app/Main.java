package tasf.app;

import tasf.experiments.StandardExperimentPipeline;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Punto de entrada unico del proyecto.
 * Ejecuta el pipeline estandar de experimentacion.
 */
public class Main {

    public static void main(String[] args) {
        ParametrosCli parametros = ParametrosCli.desdeArgs(args);

        try {
            List<StandardExperimentPipeline.AlgorithmSpec> algoritmos = new ArrayList<>();

            if (parametros.algoritmo == null || parametros.algoritmo.equalsIgnoreCase("ambos")) {
                algoritmos.add(new StandardExperimentPipeline.AlgorithmSpec("ALNS", () -> new tasf.strategy.alns.ALNS_RutasPlanner(parametros.semillaALNS)));
                algoritmos.add(new StandardExperimentPipeline.AlgorithmSpec("ACO", () -> new tasf.strategy.aco.ACO_RutasPlanner(parametros.semillaACO)));
            } else if (parametros.algoritmo.equalsIgnoreCase("ALNS")) {
                algoritmos.add(new StandardExperimentPipeline.AlgorithmSpec("ALNS", () -> new tasf.strategy.alns.ALNS_RutasPlanner(parametros.semillaALNS)));
            } else if (parametros.algoritmo.equalsIgnoreCase("ACO")) {
                algoritmos.add(new StandardExperimentPipeline.AlgorithmSpec("ACO", () -> new tasf.strategy.aco.ACO_RutasPlanner(parametros.semillaACO)));
            } else {
                System.err.println("Algoritmo no válido: " + parametros.algoritmo + ". Use ALNS, ACO, o ambos.");
                System.exit(1);
            }

            StandardExperimentPipeline pipeline = new StandardExperimentPipeline(
                    parametros.dataDir,
                    parametros.fechaInicioVuelos,
                    parametros.diasVuelos,
                    parametros.maxEnviosPorArchivo,
                    parametros.corridasPorAlgoritmo,
                    parametros.fechaEnviosFiltro,
                    parametros.usarDiaMaximoEnvios,
                    parametros.fechaEnviosDia,
                    parametros.barrerPorcentajeEnvios,
                    parametros.porcentajeEnviosInicial,
                    parametros.porcentajeEnviosMinimo,
                    parametros.pasoPorcentajeEnvios,
                    algoritmos,
                    parametros.ejecucionRapida
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
        private final int fechaEnviosDia;
        private final boolean barrerPorcentajeEnvios;
        private final int porcentajeEnviosInicial;
        private final int porcentajeEnviosMinimo;
        private final int pasoPorcentajeEnvios;
        private final long semillaALNS;
        private final long semillaACO;
        private final String algoritmo;
        private final boolean ejecucionRapida;

        private static int calcularDiasVuelosAutomatico() {
            Duration plazoIntercontinental = Duration.ofHours(48);
            Duration plazoMismoContinente = Duration.ofHours(24);
            Duration plazoMaximo = plazoIntercontinental.compareTo(plazoMismoContinente) > 0
                    ? plazoIntercontinental : plazoMismoContinente;
            Duration buffer = Duration.ofHours(24);
            long horasTotales = plazoMaximo.toHours() + buffer.toHours();
            return (int) Math.ceil((double) horasTotales / 24);
        }

        private ParametrosCli(
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
                long semillaALNS,
                long semillaACO,
                String algoritmo,
                boolean ejecucionRapida
        ) {
            this.dataDir = dataDir;
            this.fechaInicioVuelos = fechaInicioVuelos;
            this.diasVuelos = diasVuelos;
            this.maxEnviosPorArchivo = maxEnviosPorArchivo;
            this.corridasPorAlgoritmo = corridasPorAlgoritmo;
            this.fechaEnviosFiltro = fechaEnviosFiltro;
            this.usarDiaMaximoEnvios = usarDiaMaximoEnvios;
            this.fechaEnviosDia = fechaEnviosDia;
            this.barrerPorcentajeEnvios = barrerPorcentajeEnvios;
            this.porcentajeEnviosInicial = porcentajeEnviosInicial;
            this.porcentajeEnviosMinimo = porcentajeEnviosMinimo;
            this.pasoPorcentajeEnvios = pasoPorcentajeEnvios;
            this.semillaALNS = semillaALNS;
            this.semillaACO = semillaACO;
            this.algoritmo = algoritmo;
            this.ejecucionRapida = ejecucionRapida;
        }

        private static ParametrosCli desdeArgs(String[] args) {
            Path dataDir = Path.of("data").toAbsolutePath().normalize();
            LocalDate fechaInicioVuelos = LocalDate.of(2026, 1, 2);
            int diasVuelos = 0;
            int maxEnviosPorArchivo = 0;
            int corridasPorAlgoritmo = 10;
            LocalDate fechaEnviosFiltro = null;
            boolean usarDiaMaximoEnvios = false;
            int fechaEnviosDia = 0;
            boolean barrerPorcentajeEnvios = false;
            int porcentajeEnviosInicial = 100;
            int porcentajeEnviosMinimo = 10;
            int pasoPorcentajeEnvios = 5;
            long semillaALNS = 17L;
            long semillaACO = 17L;
            String algoritmo = null;
            boolean diasVuelosEspecificado = false;
            boolean ejecucionRapida = false;

            for (String arg : args) {
                if (arg.startsWith("--data-dir=")) {
                    dataDir = Path.of(arg.substring("--data-dir=".length())).toAbsolutePath().normalize();
                } else if (arg.startsWith("--fecha-inicio-vuelos=")) {
                    fechaInicioVuelos = LocalDate.parse(arg.substring("--fecha-inicio-vuelos=".length()));
                } else if (arg.startsWith("--dias-vuelos=")) {
                    diasVuelos = Integer.parseInt(arg.substring("--dias-vuelos=".length()));
                    diasVuelosEspecificado = true;
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
                    } else if (valor.matches("\\d+")) {
                        usarDiaMaximoEnvios = false;
                        fechaEnviosFiltro = null;
                        fechaEnviosDia = Integer.parseInt(valor);
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
                    pasoPorcentajeEnvios = Integer.parseInt(arg.substring("-- pasoPorcentajeEnvios=".length()));
                } else if (arg.startsWith("--semilla-alns=")) {
                    semillaALNS = Long.parseLong(arg.substring("--semilla-alns=".length()));
                } else if (arg.startsWith("--semilla-aco=")) {
                    semillaACO = Long.parseLong(arg.substring("--semilla-aco=".length()));
                } else if (arg.startsWith("--algoritmo=")) {
                    algoritmo = arg.substring("--algoritmo=".length()).trim();
                } else if (arg.equals("--ejecucion-rapida") || arg.equals("-r")) {
                    ejecucionRapida = true;
                }
            }

            boolean usarFechaEnvios = usarDiaMaximoEnvios || fechaEnviosFiltro != null || fechaEnviosDia > 0 || barrerPorcentajeEnvios;
            if (usarFechaEnvios && !diasVuelosEspecificado && diasVuelos == 0) {
                diasVuelos = calcularDiasVuelosAutomatico();
                System.out.println("[INFO] Días de vuelos calculados automáticamente: " + diasVuelos
                        + " (basado en plazos: 24h mismo continente, 48h intercontinental + 24h buffer)");
            }
            if (ejecucionRapida) {
                System.out.println("[INFO] Modo ejecución rápida: iteraciones reducidas, menos rutas candidatas");
            }

            return new ParametrosCli(
                    dataDir,
                    fechaInicioVuelos,
                    diasVuelos,
                    Math.max(0, maxEnviosPorArchivo),
                    Math.max(1, corridasPorAlgoritmo),
                    fechaEnviosFiltro,
                    usarDiaMaximoEnvios,
                    fechaEnviosDia,
                    barrerPorcentajeEnvios,
                    porcentajeEnviosInicial,
                    porcentajeEnviosMinimo,
                    pasoPorcentajeEnvios,
                    semillaALNS,
                    semillaACO,
                    algoritmo,
                    ejecucionRapida
            );
        }
    }
}
