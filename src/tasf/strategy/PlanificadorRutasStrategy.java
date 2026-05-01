package tasf.strategy;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.model.Ruta;

import java.util.Map;

/**
 * Interfaz para estrategias de planificación de rutas.
 * Devuelve la ruta seleccionada para cada paquete.
 * 
 * La salida es un Map donde:
 * - Clave: ID del paquete
 * - Valor: Ruta óptima seleccionada para ese paquete
 */
public interface PlanificadorRutasStrategy {
    /**
     * Planifica rutas para los paquetes usando una estrategia metaheurística.
     * 
     * @param datos Dataset con paquetes, vuelos y aeropuertos
     * @param config Configuración de simulación
     * @return Map de paquete -> ruta seleccionada
     */
    Map<String, Ruta> planificarRutas(Dataset datos, Config_Simulacion config);
}
