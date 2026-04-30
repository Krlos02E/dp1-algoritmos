package tasf.io;

import tasf.model.Aeropuerto;
import tasf.model.Vuelo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PlanesVueloParser {
    private PlanesVueloParser() {
    }

    public static List<Vuelo> parse(
            Path archivo,
            LocalDate fechaInicio,
            int dias,
            Map<String, Aeropuerto> aeropuertos
    ) throws IOException {
        List<String> lineas = Files.readAllLines(archivo);
        return parseLineas(lineas, fechaInicio, dias, aeropuertos);
    }

    public static List<Vuelo> parseLineas(
            List<String> lineas,
            LocalDate fechaInicio,
            int dias,
            Map<String, Aeropuerto> aeropuertos
    ) {
        List<Vuelo> vuelos = new ArrayList<>();
        int idx = 0;

        // Si dias <= 0, carga todos los vuelos para un período amplio (3 años)
        // Esto permite cubrir todo el rango de paquetes sin limitar artificialmente
        int diasACargar = dias > 0 ? dias : 1095;

        for (String raw : lineas) {
            String linea = raw.trim();
            if (linea.isEmpty()) {
                continue;
            }

            String[] p = linea.split("-");
            if (p.length < 5) {
                continue;
            }

            Aeropuerto origen = aeropuertos.get(p[0]);
            Aeropuerto destino = aeropuertos.get(p[1]);
            if (origen == null || destino == null) {
                continue;
            }

            LocalTime salida = LocalTime.parse(p[2]);
            LocalTime llegada = LocalTime.parse(p[3]);
            int capacidad = Integer.parseInt(p[4]);

            for (int d = 0; d < diasACargar; d++) {
                LocalDate fecha = fechaInicio.plusDays(d);
                String id = p[0] + "-" + p[1] + "-" + fecha + "-" + idx;
                vuelos.add(new Vuelo(id, origen, destino, fecha, salida, llegada, capacidad));
            }
            idx++;
        }

        return vuelos;
    }
}
