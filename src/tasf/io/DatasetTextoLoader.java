package tasf.io;

import tasf.core.Dataset;
import tasf.model.Aeropuerto;
import tasf.model.Continente;
import tasf.model.Paquete;
import tasf.model.Vuelo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DatasetTextoLoader {
    private static final Pattern PATRON_FILA_AEROPUERTO =
            Pattern.compile("^\\s*\\d+\\s+([A-Z]{4})\\s+.*?\\s+([+-]\\d+)\\s+(\\d+)\\s+Latitude.*$");
    private static final Pattern PATRON_ARCHIVO_ENVIOS =
            Pattern.compile("_envios_([A-Z]{4})_\\.txt");

    private DatasetTextoLoader() {
    }

    public static Dataset cargarDataset(
            Path carpetaDatos,
            LocalDate fechaInicio,
            int diasVuelos,
            int maxEnviosPorArchivo
    ) throws IOException {
        RutasDataset rutas = resolverRutas(carpetaDatos);

        Map<String, Aeropuerto> aeropuertos = cargarAeropuertos(rutas.archivoAeropuertos());
        List<Vuelo> vuelos = PlanesVueloParser.parse(rutas.archivoPlanesVuelo(), fechaInicio, diasVuelos, aeropuertos);
        List<Paquete> paquetes = cargarPaquetes(rutas.carpetaEnvios(), maxEnviosPorArchivo, aeropuertos);
        return new Dataset(aeropuertos, vuelos, paquetes);
    }

    public static ResultadoCargaSimulacion cargarDatasetSimulacion(
            Path carpetaDatos,
            LocalDate fechaInicioVuelos,
            int diasVuelos,
            int diasSimulacion,
            int minEnvios,
            int maxEnviosTotal,
            long randomSeed
    ) throws IOException {
        RutasDataset rutas = resolverRutas(carpetaDatos);

        Map<String, Aeropuerto> aeropuertos = cargarAeropuertos(rutas.archivoAeropuertos());
        List<Vuelo> vuelos = PlanesVueloParser.parse(rutas.archivoPlanesVuelo(), fechaInicioVuelos, diasVuelos, aeropuertos);
        List<Paquete> paquetes = cargarPaquetesCompleto(rutas.carpetaEnvios(), aeropuertos);

        LocalDate inicioSim = fechaInicioVuelos.plusDays(1);
        LocalDate finSim = inicioSim.plusDays(Math.max(1, diasSimulacion) - 1L);

        List<Paquete> filtrados = paquetes.stream()
                .filter(pk -> !pk.getFecha().isBefore(inicioSim) && !pk.getFecha().isAfter(finSim))
                .collect(Collectors.toList());

        int totalFiltrados = filtrados.size();
        if (totalFiltrados < minEnvios) {
            return new ResultadoCargaSimulacion(
                    new Dataset(aeropuertos, vuelos, List.of()),
                    paquetes.size(),
                    totalFiltrados,
                    0,
                    true,
                    "menos_de_min_envios",
                    inicioSim,
                    finSim
            );
        }

        List<Paquete> usados = filtrados;
        if (maxEnviosTotal > 0 && totalFiltrados > maxEnviosTotal) {
            ArrayList<Paquete> muestra = new ArrayList<>(filtrados);
            Collections.shuffle(muestra, new Random(randomSeed));
            usados = new ArrayList<>(muestra.subList(0, maxEnviosTotal));
        }

        return new ResultadoCargaSimulacion(
                new Dataset(aeropuertos, vuelos, usados),
                paquetes.size(),
                totalFiltrados,
                usados.size(),
                false,
                "",
                inicioSim,
                finSim
        );
    }

    public static String descripcionEstructuraEsperada(Path carpetaDatos) {
        return "Estructura esperada en " + carpetaDatos + System.lineSeparator()
                + "  - input/aeropuertos/<archivo con 'Aeropuerto' en el nombre>" + System.lineSeparator()
                + "  - input/vuelos/planes_vuelo.txt" + System.lineSeparator()
                + "  - input/envios/_envios_XXXX_.txt" + System.lineSeparator()
                + "Compatibilidad activa con estructura legacy: planes_vuelo.txt + _envios_preliminar_/";
    }

    private static RutasDataset resolverRutas(Path carpetaDatos) throws IOException {
        Path base = carpetaDatos.toAbsolutePath().normalize();

        // Estructura organizada: <base>/input/aeropuertos, <base>/input/vuelos, <base>/input/envios
        Path input = base.resolve("input");
        if (Files.isDirectory(input)) {
            Path vuelos = input.resolve("vuelos").resolve("planes_vuelo.txt");
            Path envios = input.resolve("envios");
            Path aeropuerto = localizarArchivoAeropuertos(input.resolve("aeropuertos"));
            if (Files.exists(vuelos) && Files.isDirectory(envios) && aeropuerto != null) {
                return new RutasDataset(aeropuerto, vuelos, envios);
            }
        }

        // Estructura cuando el usuario pasa directamente la carpeta input.
        Path vuelosInput = base.resolve("vuelos").resolve("planes_vuelo.txt");
        Path enviosInput = base.resolve("envios");
        Path aeropuertoInput = localizarArchivoAeropuertos(base.resolve("aeropuertos"));
        if (Files.exists(vuelosInput) && Files.isDirectory(enviosInput) && aeropuertoInput != null) {
            return new RutasDataset(aeropuertoInput, vuelosInput, enviosInput);
        }

        // Legacy: <base>/planes_vuelo.txt + <base>/_envios_preliminar_ + archivo aeropuertos en <base>.
        Path vuelosLegacy = base.resolve("planes_vuelo.txt");
        Path enviosLegacy = base.resolve("_envios_preliminar_");
        Path aeropuertoLegacy = localizarArchivoAeropuertos(base);
        if (Files.exists(vuelosLegacy) && Files.isDirectory(enviosLegacy) && aeropuertoLegacy != null) {
            return new RutasDataset(aeropuertoLegacy, vuelosLegacy, enviosLegacy);
        }

        throw new IllegalArgumentException(
                "No se pudo detectar estructura de datos valida en: " + base + System.lineSeparator()
                        + descripcionEstructuraEsperada(base)
        );
    }

    private static Path localizarArchivoAeropuertos(Path carpetaDatos) throws IOException {
        if (!Files.isDirectory(carpetaDatos)) {
            return null;
        }

        try (Stream<Path> files = Files.list(carpetaDatos)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains("aeropuerto"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Map<String, Aeropuerto> cargarAeropuertos(Path archivoAeropuertos) throws IOException {
        Map<String, Aeropuerto> aeropuertos = new HashMap<>();
        Continente continenteActual = null;

        List<String> lineas = Files.readAllLines(archivoAeropuertos, StandardCharsets.UTF_16);
        for (String raw : lineas) {
            String linea = raw.trim();
            if (linea.isEmpty()) {
                continue;
            }

            String lower = linea.toLowerCase();
            if (lower.contains("america del sur")) {
                continenteActual = Continente.AMERICA;
                continue;
            }
            if (lower.equals("europa")) {
                continenteActual = Continente.EUROPA;
                continue;
            }
            if (lower.equals("asia")) {
                continenteActual = Continente.ASIA;
                continue;
            }

            Matcher m = PATRON_FILA_AEROPUERTO.matcher(linea);
            if (!m.matches() || continenteActual == null) {
                continue;
            }

            String codigo = m.group(1);
            int gmtHoras = Integer.parseInt(m.group(2));
            int capacidad = Integer.parseInt(m.group(3));
            int gmtMinutos = gmtHoras * 60;

            aeropuertos.put(codigo, new Aeropuerto(codigo, continenteActual, gmtMinutos, capacidad));
        }

        if (aeropuertos.isEmpty()) {
            throw new IllegalStateException("No se pudieron parsear aeropuertos desde " + archivoAeropuertos);
        }

        return aeropuertos;
    }

    private static List<Paquete> cargarPaquetes(
            Path carpetaEnvios,
            int maxEnviosPorArchivo,
            Map<String, Aeropuerto> aeropuertos
    ) throws IOException {
        List<Paquete> paquetes = new ArrayList<>();
        List<Path> archivos = new ArrayList<>();

        try (Stream<Path> stream = Files.list(carpetaEnvios)) {
            stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(archivos::add);
        }

        for (Path archivo : archivos) {
            Matcher matcher = PATRON_ARCHIVO_ENVIOS.matcher(archivo.getFileName().toString());
            if (!matcher.matches()) {
                continue;
            }

            String origen = matcher.group(1);
            if (!aeropuertos.containsKey(origen)) {
                continue;
            }

            try (Stream<String> lineas = Files.lines(archivo, StandardCharsets.UTF_8)) {
                Stream<String> flujo = lineas.map(String::trim)
                        .filter(s -> !s.isEmpty())
                        ;

                if (maxEnviosPorArchivo > 0) {
                    flujo = flujo.limit(maxEnviosPorArchivo);
                }

                flujo.forEach(raw -> {
                            try {
                                Paquete parsed = Paquete.parse(raw, origen);
                                if (!aeropuertos.containsKey(parsed.getDestinoOACI())) {
                                    return;
                                }

                                String idUnico = origen + "-" + parsed.getId();
                                paquetes.add(new Paquete(
                                        idUnico,
                                        parsed.getOrigenOACI(),
                                        parsed.getFecha(),
                                        parsed.getHora(),
                                        parsed.getDestinoOACI(),
                                        parsed.getCantidad(),
                                        parsed.getReferencia()
                                ));
                            } catch (RuntimeException ignored) {
                                // Saltamos lineas mal formadas para mantener la simulacion corriendo.
                            }
                        });
            }
        }

        return paquetes;
    }

    private static List<Paquete> cargarPaquetesCompleto(
            Path carpetaEnvios,
            Map<String, Aeropuerto> aeropuertos
    ) throws IOException {
        List<Paquete> paquetes = new ArrayList<>();

        try (Stream<Path> stream = Files.list(carpetaEnvios)) {
            List<Path> archivos = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());

            for (Path archivo : archivos) {
                Matcher matcher = PATRON_ARCHIVO_ENVIOS.matcher(archivo.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }

                String origen = matcher.group(1);
                if (!aeropuertos.containsKey(origen)) {
                    continue;
                }

                try (Stream<String> lineas = Files.lines(archivo, StandardCharsets.UTF_8)) {
                    lineas.map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(raw -> agregarPaqueteSiValido(raw, origen, aeropuertos, paquetes));
                }
            }
        }

        return paquetes;
    }

    private static void agregarPaqueteSiValido(
            String raw,
            String origen,
            Map<String, Aeropuerto> aeropuertos,
            List<Paquete> paquetes
    ) {
        try {
            Paquete parsed = Paquete.parse(raw, origen);
            if (!aeropuertos.containsKey(parsed.getDestinoOACI())) {
                return;
            }

            String idUnico = origen + "-" + parsed.getId();
            paquetes.add(new Paquete(
                    idUnico,
                    parsed.getOrigenOACI(),
                    parsed.getFecha(),
                    parsed.getHora(),
                    parsed.getDestinoOACI(),
                    parsed.getCantidad(),
                    parsed.getReferencia()
            ));
        } catch (RuntimeException ignored) {
            // Saltamos lineas mal formadas para mantener la simulacion corriendo.
        }
    }

    public record ResultadoCargaSimulacion(
            Dataset dataset,
            int totalCargados,
            int totalFiltrados,
            int totalUsados,
            boolean ventanaDescartada,
            String motivo,
            LocalDate inicioSimulacion,
            LocalDate finSimulacion
    ) {
    }

    private record RutasDataset(
            Path archivoAeropuertos,
            Path archivoPlanesVuelo,
            Path carpetaEnvios
    ) {
    }
}
