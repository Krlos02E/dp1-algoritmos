package tasf.core;

import tasf.model.Paquete;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DistribucionEnviosPorDia {

    private final List<Paquete> envios;
    private final Map<LocalDate, List<Paquete>> enviosPorDia;

    public DistribucionEnviosPorDia(List<Paquete> envios) {
        if (envios == null || envios.isEmpty()) {
            throw new IllegalArgumentException("Lista de envíos no puede estar vacía");
        }
        this.envios = envios;
        this.enviosPorDia = envios.stream()
                .collect(Collectors.groupingBy(Paquete::getFecha));
    }

    public DiaSeleccionado encontrarDiaMasCercano(int objetivoEnvios) {
        if (objetivoEnvios < 0) {
            throw new IllegalArgumentException("Objetivo no puede ser negativo");
        }

        LocalDate mejorDia = null;
        int menorDiferencia = Integer.MAX_VALUE;

        for (Map.Entry<LocalDate, List<Paquete>> entry : enviosPorDia.entrySet()) {
            int cantidad = entry.getValue().size();
            int diferencia = Math.abs(cantidad - objetivoEnvios);

            if (diferencia < menorDiferencia) {
                menorDiferencia = diferencia;
                mejorDia = entry.getKey();
            }
        }

        return new DiaSeleccionado(
                mejorDia,
                enviosPorDia.get(mejorDia),
                enviosPorDia.get(mejorDia).size(),
                menorDiferencia
        );
    }

    public int obtenerCantidadEnvios(LocalDate fecha) {
        List<Paquete> enviosDia = enviosPorDia.get(fecha);
        return enviosDia != null ? enviosDia.size() : 0;
    }

    public List<Paquete> obtenerEnviosDia(LocalDate fecha) {
        List<Paquete> enviosDia = enviosPorDia.get(fecha);
        return enviosDia != null ? new ArrayList<>(enviosDia) : Collections.emptyList();
    }

    public static class DiaSeleccionado {
        public final LocalDate fecha;
        public final List<Paquete> envios;
        public final int cantidad;
        public final int diferencia;

        public DiaSeleccionado(LocalDate fecha, List<Paquete> envios, int cantidad, int diferencia) {
            this.fecha = fecha;
            this.envios = Collections.unmodifiableList(new ArrayList<>(envios));
            this.cantidad = cantidad;
            this.diferencia = diferencia;
        }

        @Override
        public String toString() {
            return String.format(
                    "DiaSeleccionado{fecha=%s, cantidad=%d, diferencia=%d}",
                    fecha, cantidad, diferencia
            );
        }
    }
}
