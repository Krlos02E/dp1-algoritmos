package tasf.strategy.alns.operators;

import tasf.config.Config_Simulacion;
import tasf.core.AsignacionPaquete;
import tasf.core.Dataset;
import tasf.model.Ruta;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DestructionOperator {
    Set<String> destroy(
            Map<String, AsignacionPaquete> propuesta,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos,
            int cantidad
    );

    String nombre();
}
