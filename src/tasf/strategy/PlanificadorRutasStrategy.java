package tasf.strategy;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.model.Ruta;

import java.util.List;
import java.util.Map;

/**
 * Interfaz para estrategias de planificación de rutas.
 * Devuelve solo rutas candidatas sin evaluación de asignación a vuelos.
 * 
 * La salida es un Map donde:
 * - Clave: ID del paquete
 * - Valor: Lista de rutas candidatas (ordenadas por preferencia)
 */
public interface PlanificadorRutasStrategy {
    /**
     * Planifica rutas para los paquetes usando una estrategia metaheurística.
     * 
     * @param datos Dataset con paquetes, vuelos y aeropuertos
     * @param config Configuración de simulación
     * @return Map de paquete -> lista de rutas candidatas
     */
    Map<String, List<Ruta>> planificarRutas(Dataset datos, Config_Simulacion config);
}
