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

public class SlaBreachRemoval implements DestructionOperator {

    @Override
    public Set<String> destroy(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                               Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                               int cantidad) {
        Set<String> destruidos = new LinkedHashSet<>();
        List<ViolacionSLA> violaciones = new ArrayList<>();

        for (Map.Entry<String, AsignacionPaquete> entry : propuesta.entrySet()) {
            Paquete p = datos.getPaquetePorId(entry.getKey());
            if (p == null) continue;
            AsignacionPaquete asignacion = entry.getValue();
            if (asignacion == null || asignacion.isEmpty()) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);
            LocalDateTime ultimaLlegada = asignacion.getUltimaLlegada();

            if (ultimaLlegada.isAfter(deadline)) {
                long minutosTarde = Duration.between(deadline, ultimaLlegada).toMinutes();
                violaciones.add(new ViolacionSLA(entry.getKey(), minutosTarde));
            }
        }

        violaciones.sort((a, b) -> Long.compare(b.minutosTarde, a.minutosTarde));

        for (int i = 0; i < Math.min(cantidad, violaciones.size()); i++) {
            destruidos.add(violaciones.get(i).paqueteId);
        }
        return destruidos;
    }

    @Override
    public String nombre() {
        return "SlaBreachRemoval";
    }

    private static class ViolacionSLA {
        final String paqueteId;
        final long minutosTarde;
        ViolacionSLA(String id, long min) {
            paqueteId = id;
            minutosTarde = min;
        }
    }
}
