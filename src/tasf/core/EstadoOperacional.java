package tasf.core;

import tasf.config.Config_Simulacion;
import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EstadoOperacional {
    private final Map<String, Map<LocalDateTime, Integer>> ocupacionAeropuertoPorHora;
    private final Map<String, Integer> cargaPorVuelo;

    public EstadoOperacional() {
        this.ocupacionAeropuertoPorHora = new HashMap<>();
        this.cargaPorVuelo = new HashMap<>();
    }

    private EstadoOperacional(
            Map<String, Map<LocalDateTime, Integer>> ocupacionAeropuertoPorHora,
            Map<String, Integer> cargaPorVuelo
    ) {
        this.ocupacionAeropuertoPorHora = ocupacionAeropuertoPorHora;
        this.cargaPorVuelo = cargaPorVuelo;
    }

    public EstadoOperacional copia() {
        Map<String, Map<LocalDateTime, Integer>> ocupacionCopia = new HashMap<>();
        for (Map.Entry<String, Map<LocalDateTime, Integer>> e : ocupacionAeropuertoPorHora.entrySet()) {
            ocupacionCopia.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return new EstadoOperacional(ocupacionCopia, new HashMap<>(cargaPorVuelo));
    }

    public boolean puedeReservarIntervalo(
            Aeropuerto aeropuerto,
            LocalDateTime inicioIncl,
            LocalDateTime finExcl,
            int cantidad
    ) {
        if (!finExcl.isAfter(inicioIncl)) {
            return true;
        }

        LocalDateTime hora = inicioIncl.truncatedTo(ChronoUnit.HOURS);
        while (hora.isBefore(finExcl)) {
            int actual = getOcupacionHora(aeropuerto.getCodigoOACI(), hora);
            if (actual + cantidad > aeropuerto.getCapacidadMaxima()) {
                return false;
            }
            hora = hora.plusHours(1);
        }

        return true;
    }

    public void reservarIntervalo(
            Aeropuerto aeropuerto,
            LocalDateTime inicioIncl,
            LocalDateTime finExcl,
            int cantidad
    ) {
        if (!finExcl.isAfter(inicioIncl)) {
            return;
        }

        Map<LocalDateTime, Integer> horas =
                ocupacionAeropuertoPorHora.computeIfAbsent(aeropuerto.getCodigoOACI(), k -> new HashMap<>());

        LocalDateTime hora = inicioIncl.truncatedTo(ChronoUnit.HOURS);
        while (hora.isBefore(finExcl)) {
            horas.put(hora, horas.getOrDefault(hora, 0) + cantidad);
            hora = hora.plusHours(1);
        }
    }

    public boolean puedeReservarVuelo(Vuelo vuelo, int cantidad) {
        int actual = cargaPorVuelo.getOrDefault(vuelo.getId(), 0);
        return actual + cantidad <= vuelo.getCapacidadCarga();
    }

    public void reservarVuelo(Vuelo vuelo, int cantidad) {
        cargaPorVuelo.put(vuelo.getId(), cargaPorVuelo.getOrDefault(vuelo.getId(), 0) + cantidad);
    }

    public int getOcupacionHora(String codigoOACI, LocalDateTime horaUtc) {
        return ocupacionAeropuertoPorHora
                .getOrDefault(codigoOACI, Map.of())
                .getOrDefault(horaUtc.truncatedTo(ChronoUnit.HOURS), 0);
    }

    public boolean reservarRutaSiFactible(
            Paquete paquete,
            Ruta ruta,
            LocalDateTime creacionUtc,
            Dataset datos,
            Config_Simulacion config
    ) {
        Aeropuerto aeropuertoActual = datos.getAeropuerto(paquete.getOrigenOACI());
        if (aeropuertoActual == null) {
            aeropuertoActual = datos.getAeropuerto(config.getAeropuertoHub());
        }
        if (aeropuertoActual == null) {
            return false;
        }
        LocalDateTime instanteActual = creacionUtc;
        List<Vuelo> vuelos = ruta.getVuelos();

        if (vuelos.isEmpty()) {
            return false;
        }

        LocalDateTime finSimulacionExclusivo = config.getFinSimulacionUtcExclusivo();
        if (finSimulacionExclusivo != null) {
            if (!creacionUtc.isBefore(finSimulacionExclusivo)) {
                return false;
            }
            if (!ruta.getLlegadaUtc().isBefore(finSimulacionExclusivo)) {
                return false;
            }
        }

        Duration conexionMinima = config.getMinimaConexion();

        for (int i = 0; i < vuelos.size(); i++) {
            Vuelo vuelo = vuelos.get(i);
            if (!vuelo.getOrigen().getCodigoOACI().equals(aeropuertoActual.getCodigoOACI())) {
                return false;
            }

            LocalDateTime salida = vuelo.getSalidaUtc();
            Duration esperaRequerida = i == 0 ? Duration.ZERO : conexionMinima;
            if (salida.isBefore(instanteActual.plus(esperaRequerida))) {
                return false;
            }
            if (finSimulacionExclusivo != null && !salida.isBefore(finSimulacionExclusivo)) {
                return false;
            }
            if (finSimulacionExclusivo != null && !vuelo.getLlegadaUtc().isBefore(finSimulacionExclusivo)) {
                return false;
            }

            if (!puedeReservarIntervalo(aeropuertoActual, instanteActual, salida, paquete.getCantidad())) {
                return false;
            }
            if (!puedeReservarVuelo(vuelo, paquete.getCantidad())) {
                return false;
            }

            aeropuertoActual = vuelo.getDestino();
            instanteActual = vuelo.getLlegadaUtc();
        }

        if (!aeropuertoActual.getCodigoOACI().equals(paquete.getDestinoOACI())) {
            return false;
        }

        aeropuertoActual = datos.getAeropuerto(paquete.getOrigenOACI());
        if (aeropuertoActual == null) {
            aeropuertoActual = datos.getAeropuerto(config.getAeropuertoHub());
        }
        if (aeropuertoActual == null) {
            return false;
        }
        instanteActual = creacionUtc;
        for (Vuelo vuelo : vuelos) {
            reservarIntervalo(aeropuertoActual, instanteActual, vuelo.getSalidaUtc(), paquete.getCantidad());
            reservarVuelo(vuelo, paquete.getCantidad());
            aeropuertoActual = vuelo.getDestino();
            instanteActual = vuelo.getLlegadaUtc();
        }

        return true;
    }
}
