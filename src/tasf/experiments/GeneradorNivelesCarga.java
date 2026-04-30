package tasf.experiments;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generador de niveles de carga para experimentos logísticos.
 * 
 * Permite generar configuraciones de carga (en porcentaje o unidades)
 * para ejecutar experimentos con diferentes escenarios.
 * 
 * Ejemplo:
 *   GeneradorNivelesCarga generador = new GeneradorNivelesCarga(15000);
 *   List<Integer> niveles = generador.generarNiveles(0.20, 0.70, 5);
 *   // Retorna: [3000, 5250, 7500, 9750, 12000]
 */
public class GeneradorNivelesCarga {
    
    private final int capacidadMaxima;
    
    // Configuración predeterminada
    private static final double PORCENTAJE_MINIMO_DEFECTO = 0.20;  // 20%
    private static final double PORCENTAJE_MAXIMO_DEFECTO = 0.70;  // 70%
    private static final int NIVELES_DEFECTO = 5;

    /**
     * Constructor con capacidad máxima.
     * 
     * @param capacidadMaxima Capacidad máxima diaria en maletas
     */
    public GeneradorNivelesCarga(int capacidadMaxima) {
        if (capacidadMaxima <= 0) {
            throw new IllegalArgumentException("Capacidad máxima debe ser mayor a 0");
        }
        this.capacidadMaxima = capacidadMaxima;
    }

    /**
     * Genera niveles de carga con configuración predeterminada.
     * 
     * Rango: 20% a 70%
     * Niveles: 5
     * Distribución: lineal
     * 
     * @return Lista de capacidades en maletas para cada nivel
     * 
     * Ejemplo:
     *   List<Integer> niveles = generador.generarNivelesDefecto();
     *   // Output: [3000, 5250, 7500, 9750, 12000]
     */
    public List<Integer> generarNivelesDefecto() {
        return generarNiveles(
                PORCENTAJE_MINIMO_DEFECTO,
                PORCENTAJE_MAXIMO_DEFECTO,
                NIVELES_DEFECTO
        );
    }

    /**
     * Genera niveles de carga especificando el rango y cantidad.
     * 
     * @param porcentajeMinimo Porcentaje mínimo (0.0 a 1.0)
     * @param porcentajeMaximo Porcentaje máximo (0.0 a 1.0)
     * @param cantidadNiveles Cantidad de niveles a generar (mínimo 1)
     * @return Lista de capacidades en maletas
     * 
     * Ejemplo:
     *   List<Integer> niveles = generador.generarNiveles(0.30, 0.80, 4);
     *   // Output: [4500, 7000, 9500, 12000]
     */
    public List<Integer> generarNiveles(
            double porcentajeMinimo,
            double porcentajeMaximo,
            int cantidadNiveles
    ) {
        validarParametros(porcentajeMinimo, porcentajeMaximo, cantidadNiveles);
        
        if (cantidadNiveles == 1) {
            return List.of((int)(capacidadMaxima * porcentajeMaximo));
        }
        
        List<Integer> niveles = new ArrayList<>();
        
        for (int i = 0; i < cantidadNiveles; i++) {
            // Interpolación lineal entre min y max
            double proporcion = (double) i / (cantidadNiveles - 1);
            double porcentaje = porcentajeMinimo + 
                    (porcentajeMaximo - porcentajeMinimo) * proporcion;
            
            int capacidad = (int) Math.round(capacidadMaxima * porcentaje);
            niveles.add(capacidad);
        }
        
        return niveles;
    }

    /**
     * Genera niveles de carga con distribución logarítmica.
     * Útil cuando quieres más granularidad en niveles bajos.
     * 
     * @param porcentajeMinimo Porcentaje mínimo
     * @param porcentajeMaximo Porcentaje máximo
     * @param cantidadNiveles Cantidad de niveles
     * @return Lista de capacidades con distribución logarítmica
     * 
     * Ejemplo:
     *   List<Integer> niveles = generador.generarNivelesLogaritmico(0.20, 0.70, 5);
     *   // Output: [3000, 4200, 6000, 8500, 12000] (más granular en bajos)
     */
    public List<Integer> generarNivelesLogaritmico(
            double porcentajeMinimo,
            double porcentajeMaximo,
            int cantidadNiveles
    ) {
        validarParametros(porcentajeMinimo, porcentajeMaximo, cantidadNiveles);
        
        List<Integer> niveles = new ArrayList<>();
        
        // Convertir porcentajes a rangos logarítmicos
        double logMin = Math.log(porcentajeMinimo);
        double logMax = Math.log(porcentajeMaximo);
        
        for (int i = 0; i < cantidadNiveles; i++) {
            double proporcion = (double) i / (cantidadNiveles - 1);
            double logPorcentaje = logMin + (logMax - logMin) * proporcion;
            double porcentaje = Math.exp(logPorcentaje);
            
            int capacidad = (int) Math.round(capacidadMaxima * porcentaje);
            niveles.add(capacidad);
        }
        
        return niveles;
    }

    /**
     * Genera niveles de carga personalizados especificando los porcentajes exactos.
     * 
     * @param porcentajes Array de porcentajes (0.0 a 1.0)
     * @return Lista de capacidades correspondientes a los porcentajes
     * 
     * Ejemplo:
     *   List<Integer> niveles = generador.generarNivelesPersonalizados(
     *       0.25, 0.40, 0.60, 0.80
     *   );
     *   // Output: [3750, 6000, 9000, 12000]
     */
    public List<Integer> generarNivelesPersonalizados(double... porcentajes) {
        if (porcentajes.length == 0) {
            throw new IllegalArgumentException("Debe proporcionar al menos un porcentaje");
        }
        
        return new ArrayList<>(
                java.util.Arrays.stream(porcentajes)
                        .peek(p -> {
                            if (p < 0 || p > 1) {
                                throw new IllegalArgumentException(
                                        "Porcentaje debe estar entre 0 y 1: " + p);
                            }
                        })
                        .sorted()
                        .mapToInt(p -> (int) Math.round(capacidadMaxima * p))
                        .boxed()
                        .distinct()
                        .collect(Collectors.toList())
        );
    }

    /**
     * Genera niveles de carga con distribución uniforme en intervalos.
     * Divide el rango en N intervalos iguales.
     * 
     * @param porcentajeMinimo Porcentaje mínimo
     * @param porcentajeMaximo Porcentaje máximo
     * @param cantidadNiveles Cantidad de niveles
     * @return Lista de capacidades distribuidas uniformemente
     * 
     * Ejemplo:
     *   List<Integer> niveles = generador.generarNivelesUniforme(0.20, 0.70, 5);
     *   // Output: [3000, 5250, 7500, 9750, 12000]
     */
    public List<Integer> generarNivelesUniforme(
            double porcentajeMinimo,
            double porcentajeMaximo,
            int cantidadNiveles
    ) {
        // Alias para método principal (distribución lineal = uniforme)
        return generarNiveles(porcentajeMinimo, porcentajeMaximo, cantidadNiveles);
    }

    /**
     * Genera niveles de carga basados en cuartiles (25%, 50%, 75%, 100%).
     * Útil para análisis estándar.
     * 
     * @return Lista de 4 niveles: cuartil 1, 2, 3, máximo
     * 
     * Ejemplo:
     *   List<Integer> cuartiles = generador.generarCuartiles();
     *   // Output: [3750, 7500, 11250, 15000]
     */
    public List<Integer> generarCuartiles() {
        return generarNivelesPersonalizados(0.25, 0.50, 0.75, 1.0);
    }

    /**
     * Genera niveles de carga basados en deciles (10%, 20%, ..., 100%).
     * Útil para análisis detallado.
     * 
     * @return Lista de 10 niveles
     * 
     * Ejemplo:
     *   List<Integer> deciles = generador.generarDeciles();
     *   // Output: [1500, 3000, 4500, 6000, 7500, 9000, 10500, 12000, 13500, 15000]
     */
    public List<Integer> generarDeciles() {
        return generarNivelesPersonalizados(
                0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0
        );
    }

    /**
     * Obtiene la información de un nivel específico.
     * 
     * @param capacidadNivel Capacidad en maletas de un nivel
     * @return Información del nivel (porcentaje, maletas, etc.)
     * 
     * Ejemplo:
     *   NivelCargaInfo info = generador.obtenerInfoNivel(5000);
     *   System.out.println(info);
     *   // Output: NivelCargaInfo{capacidad=5000, porcentaje=33.33%, ...}
     */
    public NivelCargaInfo obtenerInfoNivel(int capacidadNivel) {
        double porcentaje = (double) capacidadNivel / capacidadMaxima;
        return new NivelCargaInfo(capacidadNivel, porcentaje, capacidadMaxima);
    }

    /**
     * Valida los parámetros de entrada.
     */
    private void validarParametros(
            double porcentajeMinimo,
            double porcentajeMaximo,
            int cantidadNiveles
    ) {
        if (porcentajeMinimo < 0 || porcentajeMinimo > 1) {
            throw new IllegalArgumentException(
                    "Porcentaje mínimo debe estar entre 0 y 1: " + porcentajeMinimo);
        }
        if (porcentajeMaximo < 0 || porcentajeMaximo > 1) {
            throw new IllegalArgumentException(
                    "Porcentaje máximo debe estar entre 0 y 1: " + porcentajeMaximo);
        }
        if (porcentajeMinimo > porcentajeMaximo) {
            throw new IllegalArgumentException(
                    "Porcentaje mínimo debe ser menor o igual a máximo");
        }
        if (cantidadNiveles < 1) {
            throw new IllegalArgumentException(
                    "Cantidad de niveles debe ser al menos 1");
        }
    }

    /**
     * Clase interna para información de un nivel de carga.
     */
    public static class NivelCargaInfo {
        public final int capacidad;
        public final double porcentaje;
        public final int capacidadMaxima;

        public NivelCargaInfo(int capacidad, double porcentaje, int capacidadMaxima) {
            this.capacidad = capacidad;
            this.porcentaje = porcentaje;
            this.capacidadMaxima = capacidadMaxima;
        }

        @Override
        public String toString() {
            return String.format(
                    "NivelCargaInfo{capacidad=%d, porcentaje=%.2f%%, maxima=%d}",
                    capacidad, porcentaje * 100, capacidadMaxima
            );
        }

        /**
         * Retorna una representación amigable para reportes.
         */
        public String toReporte() {
            return String.format("%d maletas (%.0f%%)", 
                    capacidad, porcentaje * 100);
        }
    }

    /**
     * Obtiene la capacidad máxima configurada.
     */
    public int getCapacidadMaxima() {
        return capacidadMaxima;
    }

    /**
     * Retorna una representación en string.
     */
    @Override
    public String toString() {
        return "GeneradorNivelesCarga{" +
                "capacidadMaxima=" + capacidadMaxima +
                '}';
    }

    /**
     * Ejemplo de uso.
     */
    public static void main(String[] args) {
        System.out.println("=== Ejemplos de Uso: GeneradorNivelesCarga ===\n");

        int capacidadMaxima = 15000;
        GeneradorNivelesCarga generador = new GeneradorNivelesCarga(capacidadMaxima);

        // Ejemplo 1: Configuración predeterminada
        System.out.println("1. Niveles por defecto (20%-70%, 5 niveles):");
        List<Integer> nivelesDefecto = generador.generarNivelesDefecto();
        nivelesDefecto.forEach(n -> System.out.println("   " + n));

        // Ejemplo 2: Personalizado
        System.out.println("\n2. Personalizado (30%-80%, 4 niveles):");
        List<Integer> nivelesPersonalizados = generador.generarNiveles(0.30, 0.80, 4);
        nivelesPersonalizados.forEach(n -> System.out.println("   " + n));

        // Ejemplo 3: Porcentajes específicos
        System.out.println("\n3. Porcentajes específicos (25%, 50%, 75%):");
        List<Integer> nivelesEspecificos = generador.generarNivelesPersonalizados(
                0.25, 0.50, 0.75
        );
        nivelesEspecificos.forEach(n -> System.out.println("   " + n));

        // Ejemplo 4: Cuartiles
        System.out.println("\n4. Cuartiles:");
        generador.generarCuartiles().forEach(n -> System.out.println("   " + n));

        // Ejemplo 5: Distribución logarítmica
        System.out.println("\n5. Distribución logarítmica (20%-70%, 5 niveles):");
        List<Integer> nivelesLog = generador.generarNivelesLogaritmico(0.20, 0.70, 5);
        nivelesLog.forEach(n -> System.out.println("   " + n));
    }
}
