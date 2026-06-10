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

public class RegretRepair implements RepairOperator {

    private static final List<Ruta> EMPTY_RUTA_LIST = List.of();

    @Override
    public void repair(Map<String, AsignacionPaquete> propuesta, List<String> ids,
                       Dataset datos, Config_Simulacion config,
                       Map<String, List<Ruta>> candidatos, EstadoOperacional estado) {
        Set<String> pendientes = new LinkedHashSet<>(ids);
        Map<String, Integer> remanentes = new HashMap<>();
        for (String id : ids) {
            Paquete p = datos.getPaquetePorId(id);
            if (p != null) remanentes.put(id, p.getCantidad());
        }

        while (!pendientes.isEmpty()) {
            String mejorId = null;
            double mejorRegret = Double.NEGATIVE_INFINITY;
            Ruta mejorRutaParaMejorId = null;
            int mejorCantidadParaMejorId = 0;

            List<String> aRemover = new ArrayList<>();

            for (String id : pendientes) {
                Paquete p = datos.getPaquetePorId(id);
                if (p == null) { aRemover.add(id); continue; }
                int remanente = remanentes.getOrDefault(id, 0);
                if (remanente <= 0) { aRemover.add(id); continue; }

                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
                Duration plazo = PlanificacionUtils.getPlazoObjetivo(p, datos, config);
                LocalDateTime deadline = creacion.plus(plazo);
                List<Ruta> rutas = candidatos.getOrDefault(id, EMPTY_RUTA_LIST);

                List<Ruta> rutasValidas = new ArrayList<>();
                List<Ruta> rutasFallback = new ArrayList<>();
                for (Ruta r : rutas) {
                    if (!r.getLlegadaUtc().isAfter(deadline)) rutasValidas.add(r);
                    else rutasFallback.add(r);
                }

                List<Ruta> aEvaluar = rutasValidas.isEmpty() ? rutasFallback : rutasValidas;
                if (aEvaluar.isEmpty()) { aRemover.add(id); continue; }

                aEvaluar.sort((r1, r2) -> Double.compare(
                        PlanificacionUtils.evaluarRutaIndividual(p, r1, datos, config),
                        PlanificacionUtils.evaluarRutaIndividual(p, r2, datos, config)));
                int maxEval = Math.min(10, aEvaluar.size());

                double best = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
                Ruta bestRuta = null;
                int bestCantidad = 0;
                for (int i = 0; i < maxEval; i++) {
                    Ruta r = aEvaluar.get(i);
                    int capResidual = estado.capacidadResidualRuta(p, r, creacion, datos, config);
                    if (capResidual <= 0) continue;
                    int cantidadAsignar = Math.min(remanente, capResidual);
                    double score = PlanificacionUtils.evaluarRutaIndividualLight(p, r, estado, datos, config);
                    if (score < best) { second = best; best = score; bestRuta = r; bestCantidad = cantidadAsignar; }
                    else if (score < second) { second = score; }
                }
                if (bestRuta == null) { aRemover.add(id); continue; }
                if (second == Double.POSITIVE_INFINITY) second = best + 500.0;
                double regret = second - best;
                if (regret > mejorRegret) {
                    mejorRegret = regret;
                    mejorId = id;
                    mejorRutaParaMejorId = bestRuta;
                    mejorCantidadParaMejorId = bestCantidad;
                }
            }

            pendientes.removeAll(aRemover);

            if (mejorId == null) break;

            Paquete p = datos.getPaquetePorId(mejorId);
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);

            if (estado.reservarRutaSiFactible(p, mejorRutaParaMejorId, creacion, datos, config, mejorCantidadParaMejorId)) {
                AsignacionPaquete existente = propuesta.get(mejorId);
                if (existente == null) {
                    existente = new AsignacionPaquete(new ArrayList<>());
                } else {
                    existente = existente.copia();
                }
                existente.agregarRuta(mejorRutaParaMejorId, mejorCantidadParaMejorId);
                propuesta.put(mejorId, existente);

                int nuevoRemanente = remanentes.getOrDefault(mejorId, 0) - mejorCantidadParaMejorId;
                remanentes.put(mejorId, nuevoRemanente);
                if (nuevoRemanente <= 0) {
                    pendientes.remove(mejorId);
                }
            } else {
                pendientes.remove(mejorId);
            }
        }
    }

    @Override
    public String nombre() {
        return "RegretRepair";
    }
}
