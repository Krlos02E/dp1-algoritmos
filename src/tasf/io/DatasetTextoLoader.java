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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        return cargarDataset(carpetaDatos, fechaInicio, diasVuelos, maxEnviosPorArchivo, null);
    }

    public static Dataset cargarDataset(
            Path carpetaDatos,
            LocalDate fechaInicio,
            int diasVuelos,
            int maxEnviosPorArchivo,
            Set<LocalDate> fechasFiltro
    ) throws IOException {
        RutasDataset rutas = resolverRutas(carpetaDatos);

        Map<String, Aeropuerto> aeropuertos = cargarAeropuertos(rutas.archivoAeropuertos());
        int diasReales = diasVuelos > 0 ? diasVuelos : calcularDiasVuelos(fechasFiltro, fechaInicio);
        List<Vuelo> vuelos = PlanesVueloParser.parse(rutas.archivoPlanesVuelo(), fechaInicio, diasReales, aeropuertos);
        List<Paquete> paquetes = cargarPaquetes(rutas.carpetaEnvios(), maxEnviosPorArchivo, aeropuertos, fechasFiltro);
        return new Dataset(aeropuertos, vuelos, paquetes);
    }

    public static Map<LocalDate, Integer> escanearConteoPorDia(Path carpetaEnvios, Map<String, Aeropuerto> aeropuertos) throws IOException {
        Map<LocalDate, Integer> conteo = new HashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.BASIC_ISO_DATE;

        try (Stream<Path> stream = Files.list(carpetaEnvios)) {
            stream
                    .filter(Files::isRegularFile)
                    .filter(p -> PATRON_ARCHIVO_ENVIOS.matcher(p.getFileName().toString()).matches())
                    .forEach(archivo -> {
                        Matcher matcherArchivo = PATRON_ARCHIVO_ENVIOS.matcher(archivo.getFileName().toString());
                        if (!matcherArchivo.matches()) return;
                        String origen = matcherArchivo.group(1);
                        Aeropuerto aeropuertoOrigen = aeropuertos.get(origen);
                        if (aeropuertoOrigen == null) return;

                        try (Stream<String> lineas = Files.lines(archivo, StandardCharsets.UTF_8)) {
                            lineas.forEach(raw -> {
                                String linea = raw.trim();
                                if (linea.isEmpty()) return;
                                int dash1 = linea.indexOf('-');
                                if (dash1 < 0) return;
                                int dash2 = linea.indexOf('-', dash1 + 1);
                                if (dash2 < 0) return;
                                String fechaStr = linea.substring(dash1 + 1, dash2);
                                if (fechaStr.length() != 8) return;
                                try {
                                    LocalDate fechaLocal = LocalDate.parse(fechaStr, fmt);
                                    // Extraer hora local (hh y mm)
                                    String[] parts = linea.split("-");
                                    if (parts.length >= 4) {
                                        int hh = 0, mm = 0;
                                        if (parts[2].contains(":")) {
                                            String[] horaParts = parts[2].split(":");
                                            hh = Integer.parseInt(horaParts[0]);
                                            mm = Integer.parseInt(horaParts[1]);
                                        } else if (parts.length >= 5) {
                                            hh = Integer.parseInt(parts[2]);
                                            mm = Integer.parseInt(parts[3]);
                                        }
                                        // Convertir a UTC
                                        LocalDateTime utc = aeropuertoOrigen.convertirLocalAUTC(fechaLocal, LocalTime.of(hh, mm));
                                        conteo.merge(utc.toLocalDate(), 1, Integer::sum);
                                    }
                                } catch (DateTimeParseException ignored) {
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return conteo;
    }

    private static int calcularDiasVuelos(Set<LocalDate> fechasFiltro, LocalDate fechaInicio) {
        if (fechasFiltro == null || fechasFiltro.isEmpty()) {
            return 1095;
        }
        LocalDate maxFecha = fechasFiltro.stream().max(LocalDate::compareTo).orElse(fechaInicio);
        long diff = maxFecha.toEpochDay() - fechaInicio.toEpochDay();
        return Math.max(1, (int) diff + 13);
    }

    public static String descripcionEstructuraEsperada(Path carpetaDatos) {
        return "Estructura esperada en " + carpetaDatos + System.lineSeparator()
                + "  - input/aeropuertos/<archivo con 'Aeropuerto' en el nombre>" + System.lineSeparator()
                + "  - input/vuelos/planes_vuelo.txt" + System.lineSeparator()
                + "  - input/envios/_envios_XXXX_.txt" + System.lineSeparator()
                + "Compatibilidad activa con estructura legacy: planes_vuelo.txt + _envios_preliminar_/";
    }

    public static RutasDataset resolverRutas(Path carpetaDatos) throws IOException {
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

    public static Map<String, Aeropuerto> cargarAeropuertos(Path archivoAeropuertos) throws IOException {
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
            Map<String, Aeropuerto> aeropuertos,
            Set<LocalDate> fechasFiltro
    ) throws IOException {
        boolean filtrarPorFecha = fechasFiltro != null && !fechasFiltro.isEmpty();

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
                        .filter(s -> !s.isEmpty());

                if (maxEnviosPorArchivo > 0) {
                    flujo = flujo.limit(maxEnviosPorArchivo);
                }

                flujo.forEach(raw -> {
                            try {
                                Paquete parsed = Paquete.parse(raw, origen);
                                if (!aeropuertos.containsKey(parsed.getDestinoOACI())) {
                                    return;
                                }

                                // Convertir a UTC usando el aeropuerto de origen
                                Aeropuerto aeropuertoOrigen = aeropuertos.get(origen);
                                LocalDateTime utc = aeropuertoOrigen.convertirLocalAUTC(parsed.getFecha(), parsed.getHora());

                                // Filtrar por fecha EN UTC (después de conversión)
                                if (filtrarPorFecha && !fechasFiltro.contains(utc.toLocalDate())) {
                                    return;
                                }

                                String idUnico = origen + "-" + parsed.getId();
                                paquetes.add(new Paquete(
                                        idUnico,
                                        parsed.getOrigenOACI(),
                                        utc.toLocalDate(),  // Fecha UTC
                                        utc.toLocalTime(),   // Hora UTC
                                        parsed.getDestinoOACI(),
                                        parsed.getCantidad(),
                                        parsed.getReferencia()
                                ));
                            } catch (RuntimeException ignored) {
                            }
                        });
            }
        }

        return paquetes;
    }

    public record RutasDataset(
            Path archivoAeropuertos,
            Path archivoPlanesVuelo,
            Path carpetaEnvios
    ) {
    }
}
