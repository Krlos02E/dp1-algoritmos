package tasf.strategy.alns.operators;

import tasf.config.Config_Simulacion;
import tasf.core.AsignacionPaquete;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.model.Ruta;

import java.util.List;
import java.util.Map;

public interface RepairOperator {
    void repair(
            Map<String, AsignacionPaquete> propuesta,
            List<String> ids,
            Dataset datos,
            Config_Simulacion config,
            Map<String, List<Ruta>> candidatos,
            EstadoOperacional estado
    );

    String nombre();
}
