package tasf.core;

import tasf.model.Vuelo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calcula métricas de capacidad diaria del sistema logístico.
 * 
 * Proporciona funciones para:
 * - Calcular capacidad total de un día específico
 * - Obtener capacidades para múltiples días
 * - Encontrar el día con mayor/menor capacidad
 * - Calcular estadísticas de capacidad
 */
public class CapacidadDiariaCalculadora {

    private final List<Vuelo> vuelos;

    /**
     * Constructor con lista de vuelos.
     * 
     * @param vuelos Lista de vuelos disponibles
     */
    public CapacidadDiariaCalculadora(List<Vuelo> vuelos) {
        this.vuelos = vuelos;
    }

    /**
     * Calcula la capacidad total de todos los vuelos en un día específico.
     * 
     * @param fecha La fecha para la cual calcular la capacidad (sin hora)
     * @return Capacidad total en maletas para ese día
     * 
     * Ejemplo:
     *   int capacidad = calculadora.capacidadDiaria(LocalDate.of(2025, 1, 15));
     *   // Retorna: 15000 (suma de todas las maletas de ese día)
     */
    public int capacidadDiaria(LocalDate fecha) {
        return vuelos.stream()
                .filter(v -> v.getSalidaUtc().toLocalDate().equals(fecha))
                .mapToInt(Vuelo::getCapacidadCarga)
                .sum();
    }

    /**
     * Calcula la capacidad total de un rango de fechas.
     * 
     * @param fechaInicio Fecha inicial (inclusiva)
     * @param fechaFin Fecha final (inclusiva)
     * @return Capacidad total en el rango
     * 
     * Ejemplo:
     *   int capacidad = calculadora.capacidadRango(
     *       LocalDate.of(2025, 1, 1),
     *       LocalDate.of(2025, 1, 31)
     *   );
     */
    public int capacidadRango(LocalDate fechaInicio, LocalDate fechaFin) {
        return vuelos.stream()
                .filter(v -> {
                    LocalDate fecha = v.getSalidaUtc().toLocalDate();
                    return !fecha.isBefore(fechaInicio) && !fecha.isAfter(fechaFin);
                })
                .mapToInt(Vuelo::getCapacidadCarga)
                .sum();
    }

    /**
     * Obtiene un mapa con la capacidad diaria para cada día.
     * Solo incluye días con vuelos.
     * 
     * @return Map de LocalDate → capacidad total
     * 
     * Ejemplo:
     *   Map<LocalDate, Integer> capacidades = calculadora.capacidadPorDia();
     *   capacidades.forEach((fecha, cap) -> 
     *       System.out.println(fecha + ": " + cap)
     *   );
     */
    public Map<LocalDate, Integer> capacidadPorDia() {
        return vuelos.stream()
                .collect(Collectors.groupingBy(
                        v -> v.getSalidaUtc().toLocalDate(),
                        Collectors.summingInt(Vuelo::getCapacidadCarga)
                ));
    }

    /**
     * Calcula la capacidad promedio diaria.
     * 
     * @return Capacidad promedio en maletas/día
     * 
     * Ejemplo:
     *   double promedio = calculadora.capacidadPromedioDiaria();
     *   // Retorna: 12500.5
     */
    public double capacidadPromedioDiaria() {
        Map<LocalDate, Integer> porDia = capacidadPorDia();
        if (porDia.isEmpty()) {
            return 0.0;
        }
        int total = porDia.values().stream().mapToInt(Integer::intValue).sum();
        return (double) total / porDia.size();
    }

    /**
     * Encuentra el día con mayor capacidad.
     * 
     * @return LocalDate del día con más capacidad, o null si no hay vuelos
     * 
     * Ejemplo:
     *   LocalDate diaMax = calculadora.diaConMayorCapacidad();
     *   int capacidad = calculadora.capacidadDiaria(diaMax);
     *   System.out.println("Máximo: " + diaMax + " (" + capacidad + ")");
     */
    public LocalDate diaConMayorCapacidad() {
        Map<LocalDate, Integer> porDia = capacidadPorDia();
        return porDia.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Encuentra el día con menor capacidad.
     * 
     * @return LocalDate del día con menos capacidad, o null si no hay vuelos
     * 
     * Ejemplo:
     *   LocalDate diaMin = calculadora.diaConMenorCapacidad();
     *   System.out.println("Mínimo: " + diaMin);
     */
    public LocalDate diaConMenorCapacidad() {
        Map<LocalDate, Integer> porDia = capacidadPorDia();
        return porDia.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Calcula estadísticas completas de capacidad.
     * 
     * @return Objeto con estadísticas (ver EstadisticasCapacidad)
     * 
     * Ejemplo:
     *   EstadisticasCapacidad stats = calculadora.estadisticas();
     *   System.out.println("Total: " + stats.total);
     *   System.out.println("Promedio: " + stats.promedio);
     *   System.out.println("Máximo: " + stats.maximo);
     */
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

    /**
     * Obtiene los N días con mayor capacidad.
     * 
     * @param n Número de días a retornar
     * @return Lista de pares (fecha, capacidad) ordenada descendente
     * 
     * Ejemplo:
     *   var topDias = calculadora.topDiasCapacidad(5);
     *   topDias.forEach(par -> 
     *       System.out.println(par.fecha + ": " + par.capacidad)
     *   );
     */
    public List<DiasCapacidadPar> topDiasCapacidad(int n) {
        return capacidadPorDia().entrySet().stream()
                .sorted(Map.Entry.<LocalDate, Integer>comparingByValue().reversed())
                .limit(n)
                .map(e -> new DiasCapacidadPar(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Filtra días que cumplan una capacidad mínima.
     * 
     * @param capacidadMinima Capacidad mínima requerida
     * @return Mapa de días que cumplen con la capacidad mínima
     * 
     * Ejemplo:
     *   var diasAltos = calculadora.diasConCapacidadMinima(10000);
     *   System.out.println("Días con 10K+ maletas: " + diasAltos.size());
     */
    public Map<LocalDate, Integer> diasConCapacidadMinima(int capacidadMinima) {
        return capacidadPorDia().entrySet().stream()
                .filter(e -> e.getValue() >= capacidadMinima)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Obtiene información de ocupación promedio por hora del día.
     * Agrupa vuelos por hora UTC y calcula capacidad promedio.
     * 
     * @param fecha Fecha específica a analizar
     * @return Mapa de hora → capacidad acumulada
     * 
     * Ejemplo:
     *   var porHora = calculadora.capacidadPorHora(LocalDate.of(2025, 1, 15));
     *   porHora.forEach((hora, cap) -> 
     *       System.out.println(hora + ":00 UTC: " + cap + " maletas")
     *   );
     */
    public Map<Integer, Integer> capacidadPorHora(LocalDate fecha) {
        return vuelos.stream()
                .filter(v -> v.getSalidaUtc().toLocalDate().equals(fecha))
                .collect(Collectors.groupingBy(
                        v -> v.getSalidaUtc().getHour(),
                        Collectors.summingInt(Vuelo::getCapacidadCarga)
                ));
    }

    /**
     * Obtiene el conteo de vuelos por día.
     * 
     * @return Mapa de LocalDate → número de vuelos
     * 
     * Ejemplo:
     *   var vulosPorDia = calculadora.vuelosPorDia();
     *   System.out.println("Vuelos el 15/01: " + vulosPorDia.get(LocalDate.of(2025, 1, 15)));
     */
    public Map<LocalDate, Long> vuelosPorDia() {
        return vuelos.stream()
                .collect(Collectors.groupingBy(
                        v -> v.getSalidaUtc().toLocalDate(),
                        Collectors.counting()
                ));
    }

    /**
     * Calcula la capacidad promedio por vuelo en un día.
     * 
     * @param fecha Fecha a analizar
     * @return Capacidad promedio por vuelo
     * 
     * Ejemplo:
     *   double promPerVuelo = calculadora.capacidadPromedioVuelo(LocalDate.of(2025, 1, 15));
     *   System.out.println("Promedio por vuelo: " + String.format("%.0f", promPerVuelo));
     */
    public double capacidadPromedioVuelo(LocalDate fecha) {
        List<Vuelo> vuelosDia = vuelos.stream()
                .filter(v -> v.getSalidaUtc().toLocalDate().equals(fecha))
                .collect(Collectors.toList());
        
        if (vuelosDia.isEmpty()) {
            return 0.0;
        }
        
        int totalCapacidad = vuelosDia.stream()
                .mapToInt(Vuelo::getCapacidadCarga)
                .sum();
        
        return (double) totalCapacidad / vuelosDia.size();
    }

    /**
     * Clase interna para almacenar un par de fecha-capacidad.
     */
    public static class DiasCapacidadPar {
        public final LocalDate fecha;
        public final int capacidad;

        public DiasCapacidadPar(LocalDate fecha, int capacidad) {
            this.fecha = fecha;
            this.capacidad = capacidad;
        }

        @Override
        public String toString() {
            return fecha + ": " + capacidad + " maletas";
        }
    }

    /**
     * Clase interna para almacenar estadísticas de capacidad.
     */
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

        @Override
        public String toString() {
            return "EstadisticasCapacidad{" +
                    "total=" + total +
                    ", maximo=" + maximo +
                    ", minimo=" + minimo +
                    ", diasConVuelos=" + diasConVuelos +
                    ", promedio=" + String.format("%.2f", promedio) +
                    '}';
        }
    }

    /**
     * Ejemplo de uso completo.
     */
    public static void main(String[] args) {
        // Simulación con datos de ejemplo (reemplazar con datos reales)
        System.out.println("=== Ejemplo de Uso: CapacidadDiariaCalculadora ===\n");

        // Crear calculadora con datos reales
        // CapacidadDiariaCalculadora calc = new CapacidadDiariaCalculadora(datos.getVuelos());

        // Ejemplos de uso:
        // LocalDate fecha = LocalDate.of(2025, 1, 15);
        // 
        // // 1. Capacidad de un día específico
        // int capDia = calc.capacidadDiaria(fecha);
        // System.out.println("Capacidad 15/01: " + capDia + " maletas\n");
        //
        // // 2. Estadísticas generales
        // EstadisticasCapacidad stats = calc.estadisticas();
        // System.out.println(stats + "\n");
        //
        // // 3. Top 5 días con más capacidad
        // System.out.println("Top 5 días:");
        // calc.topDiasCapacidad(5).forEach(System.out::println);
        //
        // // 4. Capacidad por hora del día
        // System.out.println("\nCapacidad por hora (15/01):");
        // calc.capacidadPorHora(fecha).forEach((hora, cap) ->
        //     System.out.println("  " + String.format("%02d:00 UTC", hora) + ": " + cap)
        // );
    }
}
