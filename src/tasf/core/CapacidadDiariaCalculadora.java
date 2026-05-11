package tasf.core;

import tasf.model.Vuelo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CapacidadDiariaCalculadora {

    private final List<Vuelo> vuelos;

    public CapacidadDiariaCalculadora(List<Vuelo> vuelos) {
        this.vuelos = vuelos;
    }

    public Map<LocalDate, Integer> capacidadPorDia() {
        return vuelos.stream()
                .collect(Collectors.groupingBy(
                        v -> v.getSalidaUtc().toLocalDate(),
                        Collectors.summingInt(Vuelo::getCapacidadCarga)
                ));
    }

    public EstadisticasCapacidad estadisticas() {
        Map<LocalDate, Integer> porDia = capacidadPorDia();
        
        if (porDia.isEmpty()) {
            return new EstadisticasCapacidad(0, 0, 0, 0, 0.0);
        }
        
        int total = porDia.values().stream().mapToInt(Integer::intValue).sum();
        int maximo = porDia.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int minimo = porDia.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int diasConVuelos = porDia.size();
        double promedio = (double) total / diasConVuelos;
        
        return new EstadisticasCapacidad(total, maximo, minimo, diasConVuelos, promedio);
    }

    public static class EstadisticasCapacidad {
        public final int total;
        public final int maximo;
        public final int minimo;
        public final int diasConVuelos;
        public final double promedio;

        public EstadisticasCapacidad(int total, int maximo, int minimo, int diasConVuelos, double promedio) {
            this.total = total;
            this.maximo = maximo;
            this.minimo = minimo;
            this.diasConVuelos = diasConVuelos;
            this.promedio = promedio;
        }
    }
}
