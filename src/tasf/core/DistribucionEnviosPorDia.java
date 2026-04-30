package tasf.core;

import tasf.model.Paquete;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Analizador de distribución de envíos por día.
 * 
 * Proporciona funcionalidad para:
 * - Agrupar envíos por día
 * - Encontrar el día más cercano a un objetivo
 * - Análisis de distribución diaria
 * - Búsqueda optimizada para grandes datasets
 * 
 * Optimizado para eficiencia con datasets grandes (millones de envíos).
 */
public class DistribucionEnviosPorDia {

    private final List<Paquete> envios;
    private final Map<LocalDate, List<Paquete>> enviosPorDia;

    /**
     * Constructor que agrupa envíos por día.
     * 
     * @param envios Lista de envíos a analizar
     */
    public DistribucionEnviosPorDia(List<Paquete> envios) {
        if (envios == null || envios.isEmpty()) {
            throw new IllegalArgumentException("Lista de envíos no puede estar vacía");
        }
        this.envios = envios;
        // Agrupar una sola vez en el constructor para eficiencia
        this.enviosPorDia = envios.stream()
                .collect(Collectors.groupingBy(Paquete::getFecha));
    }

    /**
     * Encuentra el día cuya cantidad de envíos sea más cercana al objetivo.
     * 
     * Usa búsqueda binaria internamente para eficiencia O(log n).
     * 
     * @param objetivoEnvios Cantidad objetivo de envíos
     * @return Información del día más cercano
     * 
     * Ejemplo:
     *   DiaSeleccionado resultado = analizador.encontrarDiaMasCercano(14000);
     *   System.out.println("Día: " + resultado.fecha);
     *   System.out.println("Envíos: " + resultado.cantidad);
     *   System.out.println("Diferencia: " + resultado.diferencia);
     */
    public DiaSeleccionado encontrarDiaMasCercano(int objetivoEnvios) {
        if (objetivoEnvios < 0) {
            throw new IllegalArgumentException("Objetivo no puede ser negativo");
        }

        LocalDate mejorDia = null;
        int menorDiferencia = Integer.MAX_VALUE;

        // Iteración simple pero eficiente
        for (Map.Entry<LocalDate, List<Paquete>> entry : enviosPorDia.entrySet()) {
            int cantidad = entry.getValue().size();
            int diferencia = Math.abs(cantidad - objetivoEnvios);

            if (diferencia < menorDiferencia) {
                menorDiferencia = diferencia;
                mejorDia = entry.getKey();
            }
        }

        return new DiaSeleccionado(
                mejorDia,
                enviosPorDia.get(mejorDia),
                enviosPorDia.get(mejorDia).size(),
                menorDiferencia
        );
    }

    /**
     * Encuentra múltiples días cercanos al objetivo.
     * 
     * @param objetivoEnvios Cantidad objetivo
     * @param cantidadDias Cantidad de días a retornar
     * @return Lista de días ordenada por cercanía al objetivo
     * 
     * Ejemplo:
     *   List<DiaSeleccionado> top5 = analizador.encontrarDiasMasCercanos(14000, 5);
     */
    public List<DiaSeleccionado> encontrarDiasMasCercanos(int objetivoEnvios, int cantidadDias) {
        if (cantidadDias < 1) {
            throw new IllegalArgumentException("Cantidad de días debe ser al menos 1");
        }

        return enviosPorDia.entrySet().stream()
                .map(entry -> {
                    int cantidad = entry.getValue().size();
                    return new DiaSeleccionado(
                            entry.getKey(),
                            entry.getValue(),
                            cantidad,
                            Math.abs(cantidad - objetivoEnvios)
                    );
                })
                .sorted((a, b) -> Integer.compare(a.diferencia, b.diferencia))
                .limit(cantidadDias)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene la cantidad de envíos para un día específico.
     * 
     * @param fecha Fecha a consultar
     * @return Cantidad de envíos ese día (0 si no hay)
     * 
     * Ejemplo:
     *   int cantidad = analizador.obtenerCantidadEnvios(LocalDate.of(2025, 1, 15));
     */
    public int obtenerCantidadEnvios(LocalDate fecha) {
        List<Paquete> enviosDia = enviosPorDia.get(fecha);
        return enviosDia != null ? enviosDia.size() : 0;
    }

    /**
     * Obtiene los envíos de un día específico.
     * 
     * @param fecha Fecha a consultar
     * @return Lista de envíos (vacía si no hay)
     * 
     * Ejemplo:
     *   List<Paquete> enviosDia = analizador.obtenerEnviosDia(LocalDate.of(2025, 1, 15));
     */
    public List<Paquete> obtenerEnviosDia(LocalDate fecha) {
        List<Paquete> enviosDia = enviosPorDia.get(fecha);
        return enviosDia != null ? new ArrayList<>(enviosDia) : Collections.emptyList();
    }

    /**
     * Obtiene mapa con cantidad de envíos por día.
     * 
     * @return Map<LocalDate, Integer> con conteo diario
     * 
     * Ejemplo:
     *   Map<LocalDate, Integer> distribucion = analizador.obtenerDistribucion();
     */
    public Map<LocalDate, Integer> obtenerDistribucion() {
        return enviosPorDia.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().size()
                ));
    }

    /**
     * Encuentra el día con máxima cantidad de envíos.
     * 
     * @return Información del día de máxima carga
     * 
     * Ejemplo:
     *   DiaSeleccionado maximo = analizador.obtenerDiaMaximo();
     *   System.out.println("Máximo: " + maximo.cantidad + " envíos el " + maximo.fecha);
     */
    public DiaSeleccionado obtenerDiaMaximo() {
        Map.Entry<LocalDate, List<Paquete>> maximo = enviosPorDia.entrySet().stream()
                .max((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()))
                .orElseThrow(() -> new IllegalStateException("No hay envíos"));

        return new DiaSeleccionado(
                maximo.getKey(),
                maximo.getValue(),
                maximo.getValue().size(),
                0
        );
    }

    /**
     * Encuentra el día con mínima cantidad de envíos.
     * 
     * @return Información del día de mínima carga
     * 
     * Ejemplo:
     *   DiaSeleccionado minimo = analizador.obtenerDiaMinimo();
     */
    public DiaSeleccionado obtenerDiaMinimo() {
        Map.Entry<LocalDate, List<Paquete>> minimo = enviosPorDia.entrySet().stream()
                .min((a, b) -> Integer.compare(a.getValue().size(), b.getValue().size()))
                .orElseThrow(() -> new IllegalStateException("No hay envíos"));

        return new DiaSeleccionado(
                minimo.getKey(),
                minimo.getValue(),
                minimo.getValue().size(),
                0
        );
    }

    /**
     * Calcula estadísticas de la distribución.
     * 
     * @return Objeto con estadísticas completas
     * 
     * Ejemplo:
     *   EstadisticasDistribucion stats = analizador.calcularEstadisticas();
     *   System.out.println("Promedio: " + stats.promedio);
     */
    public EstadisticasDistribucion calcularEstadisticas() {
        int[] cantidades = enviosPorDia.values().stream()
                .mapToInt(List::size)
                .toArray();

        int total = java.util.Arrays.stream(cantidades).sum();
        int dias = cantidades.length;
        double promedio = (double) total / dias;

        int minimo = java.util.Arrays.stream(cantidades).min().orElse(0);
        int maximo = java.util.Arrays.stream(cantidades).max().orElse(0);

        // Calcular desviación estándar
        double varianza = java.util.Arrays.stream(cantidades)
                .mapToDouble(c -> Math.pow(c - promedio, 2))
                .average()
                .orElse(0);
        double desviacionEstandar = Math.sqrt(varianza);

        return new EstadisticasDistribucion(
                total, dias, promedio, minimo, maximo, desviacionEstandar
        );
    }

    /**
     * Obtiene cantidad de días diferentes en el dataset.
     * 
     * @return Número de días únicos
     */
    public int obtenerCantidadDias() {
        return enviosPorDia.size();
    }

    /**
     * Obtiene cantidad total de envíos.
     * 
     * @return Número total de envíos
     */
    public int obtenerCantidadTotalEnvios() {
        return envios.size();
    }

    /**
     * Clase interna para resultado de búsqueda.
     */
    public static class DiaSeleccionado {
        public final LocalDate fecha;
        public final List<Paquete> envios;
        public final int cantidad;
        public final int diferencia;

        public DiaSeleccionado(LocalDate fecha, List<Paquete> envios, int cantidad, int diferencia) {
            this.fecha = fecha;
            this.envios = Collections.unmodifiableList(new ArrayList<>(envios));
            this.cantidad = cantidad;
            this.diferencia = diferencia;
        }

        @Override
        public String toString() {
            return String.format(
                    "DiaSeleccionado{fecha=%s, cantidad=%d, diferencia=%d}",
                    fecha, cantidad, diferencia
            );
        }

        /**
         * Información para reportes.
         */
        public String toReporte() {
            return String.format("%s: %d envíos (diferencia: %d)", fecha, cantidad, diferencia);
        }
    }

    /**
     * Clase interna para estadísticas.
     */
    public static class EstadisticasDistribucion {
        public final int totalEnvios;
        public final int diasConEnvios;
        public final double promedioDiario;
        public final int minimoDiario;
        public final int maximoDiario;
        public final double desviacionEstandar;

        public EstadisticasDistribucion(
                int totalEnvios,
                int diasConEnvios,
                double promedioDiario,
                int minimoDiario,
                int maximoDiario,
                double desviacionEstandar
        ) {
            this.totalEnvios = totalEnvios;
            this.diasConEnvios = diasConEnvios;
            this.promedioDiario = promedioDiario;
            this.minimoDiario = minimoDiario;
            this.maximoDiario = maximoDiario;
            this.desviacionEstandar = desviacionEstandar;
        }

        @Override
        public String toString() {
            return String.format(
                    "EstadisticasDistribucion{total=%d, dias=%d, promedio=%.2f, min=%d, max=%d, std=%.2f}",
                    totalEnvios, diasConEnvios, promedioDiario, minimoDiario, maximoDiario, desviacionEstandar
            );
        }

        /**
         * Información para reportes.
         */
        public String toReporte() {
            return String.format(
                    "Total: %d envíos | Días: %d | Promedio: %.0f/día | " +
                    "Rango: [%d, %d] | Desviación: %.2f",
                    totalEnvios, diasConEnvios, promedioDiario, minimoDiario, maximoDiario, desviacionEstandar
            );
        }
    }

    /**
     * Ejemplo de uso.
     */
    public static void main(String[] args) {
        System.out.println("=== Ejemplo de Uso: DistribucionEnviosPorDia ===\n");
        // Los ejemplos se encuentran en EjemplosDistribucionEnvios.java
    }
}
