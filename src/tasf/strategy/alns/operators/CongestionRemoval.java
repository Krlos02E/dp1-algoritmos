package tasf.strategy.alns.operators;

import tasf.config.Config_Simulacion;
import tasf.core.AsignacionPaquete;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class CongestionRemoval implements DestructionOperator {

    @Override
    public Set<String> destroy(Map<String, AsignacionPaquete> propuesta, Dataset datos,
                               Config_Simulacion config, Map<String, List<Ruta>> candidatos,
                               int cantidad) {
        Map<String, Map<LocalDateTime, Integer>> ocupacion = new HashMap<>();

        for (Map.Entry<String, AsignacionPaquete> entry : propuesta.entrySet()) {
            Paquete p = datos.getPaquetePorId(entry.getKey());
            if (p == null) continue;
            AsignacionPaquete asignacion = entry.getValue();
            if (asignacion == null || asignacion.isEmpty()) continue;

            for (var rc : asignacion.getRutas()) {
                Ruta r = rc.getRuta();
                int qty = rc.getCantidad();
                LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
                LocalDateTime instante = creacion;
                Aeropuerto apActual = datos.getAeropuerto(p.getOrigenOACI());
                if (apActual == null) apActual = datos.getAeropuerto(config.getAeropuertoHub());
                if (apActual == null) continue;

                for (Vuelo v : r.getVuelos()) {
                    LocalDateTime hora = instante.truncatedTo(ChronoUnit.HOURS);
                    while (hora.isBefore(v.getSalidaUtc())) {
                        ocupacion.computeIfAbsent(apActual.getCodigoOACI(), k -> new HashMap<>())
                                .merge(hora, qty, Integer::sum);
                        hora = hora.plusHours(1);
                    }
                    instante = v.getLlegadaUtc();
                    apActual = v.getDestino();
                }
                LocalDateTime horaInicio = instante.truncatedTo(ChronoUnit.HOURS);
                ocupacion.computeIfAbsent(apActual.getCodigoOACI(), k -> new HashMap<>())
                        .merge(horaInicio, qty, Integer::sum);
            }
        }

        List<CeldaCongestion> celdas = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDateTime, Integer>> eAp : ocupacion.entrySet()) {
            String apCode = eAp.getKey();
            Aeropuerto ap = datos.getAeropuerto(apCode);
            int cap = ap != null ? ap.getCapacidadMaxima() : 400;
            for (Map.Entry<LocalDateTime, Integer> eSlot : eAp.getValue().entrySet()) {
                double ratio = (double) eSlot.getValue() / cap;
                celdas.add(new CeldaCongestion(apCode, eSlot.getKey(), ratio));
            }
        }
        celdas.sort((a, b) -> Double.compare(b.ratio, a.ratio));

        Set<String> destruidos = new LinkedHashSet<>();
        for (CeldaCongestion celda : celdas) {
            if (destruidos.size() >= cantidad) break;
            for (Map.Entry<String, AsignacionPaquete> entry : propuesta.entrySet()) {
                if (destruidos.size() >= cantidad) break;
                AsignacionPaquete asignacion = entry.getValue();
                if (asignacion == null) continue;
                for (var rc : asignacion.getRutas()) {
                    if (usaSlot(rc.getRuta(), celda.aeropuerto, celda.hora)) {
                        destruidos.add(entry.getKey());
                        break;
                    }
                }
            }
        }
        return destruidos;
    }

    private boolean usaSlot(Ruta ruta, String apCode, LocalDateTime hora) {
        for (Vuelo v : ruta.getVuelos()) {
            if (v.getOrigen().getCodigoOACI().equals(apCode)) {
                LocalDateTime h = hora;
                while (h.isBefore(v.getSalidaUtc())) {
                    if (h.equals(hora)) return true;
                    h = h.plusHours(1);
                }
            }
            if (v.getDestino().getCodigoOACI().equals(apCode)) {
                LocalDateTime llegadaHora = v.getLlegadaUtc().truncatedTo(ChronoUnit.HOURS);
                if (llegadaHora.equals(hora)) return true;
            }
        }
        return false;
    }

    @Override
    public String nombre() {
        return "CongestionRemoval";
    }

    private static class CeldaCongestion {
        final String aeropuerto;
        final LocalDateTime hora;
        final double ratio;
        CeldaCongestion(String a, LocalDateTime h, double r) {
            aeropuerto = a; hora = h; ratio = r;
        }
    }
}
