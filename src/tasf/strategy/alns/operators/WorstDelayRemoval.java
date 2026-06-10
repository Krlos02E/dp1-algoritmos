package tasf.strategy.alns.operators;

import tasf.config.Config_Simulacion;
import tasf.core.AsignacionPaquete;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.model.Paquete;
import tasf.model.Ruta;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class WorstDelayRemoval implements DestructionOperator {

    private final Random random;

    public WorstDelayRemoval(Random random) {
        this.random = random;
    }

    @Override
    public Set<String> destroy(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                               Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                               int cantidad) {
        Set<String> destruidos = new LinkedHashSet<>();
        List<String> ids = new ArrayList<>(propuesta.keySet());

        ids.sort((a, b) -> Double.compare(
                scoreDeterioro(b, propuesta, datos, config),
                scoreDeterioro(a, propuesta, datos, config)));

        for (int i = 0; i < Math.min(cantidad, ids.size()); i++) {
            destruidos.add(ids.get(i));
        }
        return destruidos;
    }

    private double scoreDeterioro(String paqueteId, Map<String, AsignacionPaquete> propuesta,
                                  Dataset datos, Config_Simulacion config) {
        AsignacionPaquete asignacion = propuesta.get(paqueteId);
        if (asignacion == null || asignacion.isEmpty()) return Double.MAX_VALUE;
        Paquete p = datos.getPaquetePorId(paqueteId);
        LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
        LocalDateTime limite = creacion.plus(PlanificacionUtils.getPlazoObjetivo(p, datos, config));
        LocalDateTime ultimaLlegada = asignacion.getUltimaLlegada();
        long tardanza = Math.max(0L, Duration.between(limite, ultimaLlegada).toMinutes());
        long duracion = Duration.between(creacion, ultimaLlegada).toMinutes();
        return tardanza * 50.0 + duracion;
    }

    @Override
    public String nombre() {
        return "WorstDelayRemoval";
    }
}
