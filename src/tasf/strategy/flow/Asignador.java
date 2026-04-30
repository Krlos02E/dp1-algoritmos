package tasf.strategy.flow;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.model.Ruta;
import tasf.model.Vuelo;

import java.util.List;
import java.util.Map;

/**
 * Strategy para asignacion deterministica de paquetes a vuelos.
 */
public interface Asignador {

    /**
     * Asigna paquetes a vuelos respetando capacidades y minimizando costo.
     *
     * @param rutasPlanificadas rutas candidatas por paquete
     * @param datos dataset con vuelos, aeropuertos y paquetes
     * @param config configuracion de simulacion
     * @return map paquete -> vuelo asignado
     */
    Map<String, Vuelo> asignar(
            Map<String, List<Ruta>> rutasPlanificadas,
            Dataset datos,
            Config_Simulacion config
    );
}
