package tasf.strategy.alns.operators;

import tasf.config.Config_Simulacion;
import tasf.core.AsignacionPaquete;
import tasf.core.Dataset;
import tasf.core.EstadoOperacional;
import tasf.core.PlanificacionUtils;
import tasf.model.Paquete;
import tasf.model.Ruta;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class GreedyRepair implements RepairOperator {

    private static final List<Ruta> EMPTY_RUTA_LIST = List.of();

    private final Random random;

    public GreedyRepair(Random random) {
        this.random = random;
    }

    @Override
    public void repair(Map<String, AsignacionPaquete> propuesta, List<String> ids,
                       Dataset datos, Config_Simulacion config,
                       Map<String, List<Ruta>> candidatos, EstadoOperacional estado) {
        List<String> trabajo = new ArrayList<>(ids);
        Collections.shuffle(trabajo, random);

        for (String id : trabajo) {
            Paquete p = datos.getPaquetePorId(id);
            if (p == null) continue;

            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
            LocalDateTime deadline = creacion.plus(plazo);
            List<Ruta> rutas = candidatos.getOrDefault(id, EMPTY_RUTA_LIST);
            if (rutas.isEmpty()) continue;

            List<Ruta> rutasValidas = new ArrayList<>();
            List<Ruta> rutasFallback = new ArrayList<>();
            for (Ruta r : rutas) {
                if (!r.getLlegadaUtc().isAfter(deadline)) rutasValidas.add(r);
                else rutasFallback.add(r);
            }

            List<Ruta> aEvaluar = rutasValidas.isEmpty() ? rutasFallback : rutasValidas;
            if (aEvaluar.isEmpty()) continue;

            aEvaluar.sort((r1, r2) -> Double.compare(
                    PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                    PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));

            int remanente = p.getCantidad();
            AsignacionPaquete asignacion = new AsignacionPaquete(new ArrayList<>());
            Set<Ruta> usadas = new HashSet<>();

            int maxEval = Math.min(aEvaluar.size(), 15);
            for (int i = 0; i < maxEval && remanente > 0; i++) {
                Ruta r = aEvaluar.get(i);
                if (usadas.contains(r)) continue;

                int capacidadResidual = estado.capacidadResidualRuta(p, r, creacion, datos, config);
                if (capacidadResidual <= 0) continue;

                int cantidadAsignar = Math.min(remanente, capacidadResidual);
                if (estado.reservarRutaSiFactible(p, r, creacion, datos, config, cantidadAsignar)) {
                    asignacion.agregarRuta(r, cantidadAsignar);
                    usadas.add(r);
                    remanente -= cantidadAsignar;
                }
            }

            if (!asignacion.isEmpty()) {
                propuesta.put(id, asignacion);
            }
        }
    }

    @Override
    public String nombre() {
        return "GreedyRepair";
    }
}
