package tasf.strategy.flow;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.model.Ruta;

import java.util.Map;

/**
 * Strategy para asignacion deterministica de paquetes a rutas seleccionadas.
 */
public interface Asignador {

    /**
     * Asigna paquetes a rutas seleccionadas respetando capacidades y factibilidad.
     *
     * @param rutasPlanificadas ruta seleccionada por paquete
     * @param datos dataset con vuelos, aeropuertos y paquetes
     * @param config configuracion de simulacion
     * @return map paquete -> ruta aceptada
     */
    Map<String, Ruta> asignar(
            Map<String, Ruta> rutasPlanificadas,
            Dataset datos,
            Config_Simulacion config
    );
}
