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

            if (parametros.algoritmo.equalsIgnoreCase("ALNS")) {
                algoritmos.add(new StandardExperimentPipeline.AlgorithmSpec("ALNS", () -> new tasf.strategy.alns.ALNS_RutasPlanner(parametros.semillaALNS)));
            } else if (parametros.algoritmo.equalsIgnoreCase("ACO")) {
                algoritmos.add(new StandardExperimentPipeline.AlgorithmSpec("ACO", () -> new tasf.strategy.aco.ACO_RutasPlanner(parametros.semillaACO)));
            } else {
                System.err.println("Algoritmo no válido: " + parametros.algoritmo + ". Use ALNS o ACO.");
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
                    parametros.duracionEnvios,
                    algoritmos
            );

            StandardExperimentPipeline.PipelineResult resultado = pipeline.ejecutar();

            System.out.println("=== Ejecucion Completada ===");
            System.out.println("Maletas/pedidos solicitados: " + resultado.totalMaletas + " maletas en " + resultado.totalPaquetes + " pedidos");
            System.out.println("Pedidos asignados: " + resultado.pedidosAsignados + " | Sin asignar: " + resultado.sinAsignar);
            System.out.println("Maletas asignadas: " + resultado.maletasAsignadas);
            System.out.println("Maletas fuera de plazo: " + resultado.fueraDePlazo);
            System.out.println("Colapso: " + (resultado.hayColapso ? "SI" : "NO"));
            System.out.println("Costo total: " + resultado.costoTotal);
            System.out.println("Duracion: " + resultado.duracionMs + " ms");
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
        private final int duracionEnvios;
        private final long semillaALNS;
        private final long semillaACO;
        private final String algoritmo;

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
            int duracionEnvios,
            long semillaALNS,
            long semillaACO,
            String algoritmo
    ) {
            this.dataDir = dataDir;
            this.fechaInicioVuelos = fechaInicioVuelos;
            this.diasVuelos = diasVuelos;
            this.maxEnviosPorArchivo = maxEnviosPorArchivo;
            this.corridasPorAlgoritmo = corridasPorAlgoritmo;
            this.fechaEnviosFiltro = fechaEnviosFiltro;
            this.usarDiaMaximoEnvios = usarDiaMaximoEnvios;
            this.fechaEnviosDia = fechaEnviosDia;
            this.duracionEnvios = duracionEnvios;
            this.semillaALNS = semillaALNS;
            this.semillaACO = semillaACO;
            this.algoritmo = algoritmo;
        }

        private static ParametrosCli desdeArgs(String[] args) {
            Path dataDir = Path.of("data").toAbsolutePath().normalize();
            LocalDate fechaInicioVuelos = LocalDate.of(2026, 1, 2);
            int diasVuelos = 3;
            int maxEnviosPorArchivo = 0;
            int corridasPorAlgoritmo = 1;
            LocalDate fechaEnviosFiltro = null;
            boolean usarDiaMaximoEnvios = false;
            int fechaEnviosDia = 100;
            int duracionEnvios = 1;
            long semillaALNS = 17L;
            long semillaACO = 17L;
            String algoritmo = "ALNS";
            boolean diasVuelosEspecificado = false;

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
                } else if (arg.startsWith("--duracion-envios=")) {
                    duracionEnvios = Integer.parseInt(arg.substring("--duracion-envios=".length()));
                } else if (arg.startsWith("--rango-envios=")) {
                    String valor = arg.substring("--rango-envios=".length()).trim();
                    if (valor.contains("-")) {
                        String[] partes = valor.split("-");
                        if (partes.length == 2) {
                            int inicio = Integer.parseInt(partes[0]);
                            int fin = Integer.parseInt(partes[1]);
                            fechaEnviosDia = inicio;
                            duracionEnvios = fin - inicio + 1;
                            usarDiaMaximoEnvios = false;
                            fechaEnviosFiltro = null;
                        }
                    } else if (valor.contains(",")) {
                        String[] dias = valor.split(",");
                        fechaEnviosDia = Integer.parseInt(dias[0].trim());
                        // For comma-separated values, we only use the first day for now
                        // A more complete implementation would need changes to StandardExperimentPipeline
                        duracionEnvios = 1;
                        usarDiaMaximoEnvios = false;
                        fechaEnviosFiltro = null;
                    }
                } else if (arg.startsWith("--semilla-alns=")) {
                    semillaALNS = Long.parseLong(arg.substring("--semilla-alns=".length()));
                } else if (arg.startsWith("--semilla-aco=")) {
                    semillaACO = Long.parseLong(arg.substring("--semilla-aco=".length()));
                } else if (arg.startsWith("--algoritmo=")) {
                    algoritmo = arg.substring("--algoritmo=".length()).trim();
                }
            }

            boolean usarFechaEnvios = usarDiaMaximoEnvios || fechaEnviosFiltro != null || fechaEnviosDia > 0;
            if (usarFechaEnvios && !diasVuelosEspecificado && diasVuelos == 0) {
                diasVuelos = calcularDiasVuelosAutomatico();
                System.out.println("[INFO] Días de vuelos calculados automáticamente: " + diasVuelos
                        + " (basado en plazos: 24h mismo continente, 48h intercontinental + 24h buffer)");
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
                     duracionEnvios,
                     semillaALNS,
                     semillaACO,
                     algoritmo
             );
        }
    }
}
