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

    public int getCargaVuelo(String vueloId) {
        return cargaPorVuelo.getOrDefault(vueloId, 0);
    }

    public boolean puedeReservarRuta(
            Paquete paquete,
            Ruta ruta,
            LocalDateTime creacionUtc,
            Dataset datos,
            Config_Simulacion config
    ) {
        return puedeReservarRuta(paquete, ruta, creacionUtc, datos, config, paquete.getCantidad());
    }

    public boolean puedeReservarRuta(
            Paquete paquete,
            Ruta ruta,
            LocalDateTime creacionUtc,
            Dataset datos,
            Config_Simulacion config,
            int cantidad
    ) {
        Aeropuerto aeropuertoActual = datos.getAeropuerto(paquete.getOrigenOACI());
        if (aeropuertoActual == null) {
            aeropuertoActual = datos.getAeropuerto(config.getAeropuertoHub());
        }
        if (aeropuertoActual == null) return false;

        LocalDateTime instanteActual = creacionUtc;
        List<Vuelo> vuelos = ruta.getVuelos();
        if (vuelos.isEmpty()) return false;

        Duration conexionMinima = config.getMinimaConexion();
        for (int i = 0; i < vuelos.size(); i++) {
            Vuelo vuelo = vuelos.get(i);
            if (!vuelo.getOrigen().getCodigoOACI().equals(aeropuertoActual.getCodigoOACI())) return false;

            LocalDateTime salida = vuelo.getSalidaUtc();
            Duration esperaRequerida = i == 0 ? Duration.ZERO : conexionMinima;
            if (salida.isBefore(instanteActual.plus(esperaRequerida))) return false;

            if (!puedeReservarIntervalo(aeropuertoActual, instanteActual, salida, cantidad)) return false;
            if (!puedeReservarVuelo(vuelo, cantidad)) return false;

            aeropuertoActual = vuelo.getDestino();
            instanteActual = vuelo.getLlegadaUtc();
        }

        if (!aeropuertoActual.getCodigoOACI().equals(paquete.getDestinoOACI())) {
            return false;
        }

        Vuelo ultimoVuelo = vuelos.get(vuelos.size() - 1);
        LocalDateTime finRecogida = ultimoVuelo.getLlegadaUtc().plus(config.getTiempoRecogidaDestino());
        if (!puedeReservarIntervalo(aeropuertoActual, ultimoVuelo.getLlegadaUtc(), finRecogida, cantidad)) {
            return false;
        }

        return true;
    }

    public boolean reservarRutaSiFactible(
            Paquete paquete,
            Ruta ruta,
            LocalDateTime creacionUtc,
            Dataset datos,
            Config_Simulacion config
    ) {
        return reservarRutaSiFactible(paquete, ruta, creacionUtc, datos, config, paquete.getCantidad());
    }

    public boolean reservarRutaSiFactible(
            Paquete paquete,
            Ruta ruta,
            LocalDateTime creacionUtc,
            Dataset datos,
            Config_Simulacion config,
            int cantidad
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

            if (!puedeReservarIntervalo(aeropuertoActual, instanteActual, salida, cantidad)) {
                return false;
            }
            if (!puedeReservarVuelo(vuelo, cantidad)) {
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
            reservarIntervalo(aeropuertoActual, instanteActual, vuelo.getSalidaUtc(), cantidad);
            reservarVuelo(vuelo, cantidad);
            aeropuertoActual = vuelo.getDestino();
            instanteActual = vuelo.getLlegadaUtc();
        }

        Vuelo ultimoVuelo = vuelos.get(vuelos.size() - 1);
        LocalDateTime finRecogida = ultimoVuelo.getLlegadaUtc().plus(config.getTiempoRecogidaDestino());
        reservarIntervalo(aeropuertoActual, ultimoVuelo.getLlegadaUtc(), finRecogida, cantidad);

        return true;
    }

    public int capacidadResidualRuta(
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
        if (aeropuertoActual == null) return 0;

        LocalDateTime instanteActual = creacionUtc;
        List<Vuelo> vuelos = ruta.getVuelos();
        if (vuelos.isEmpty()) return 0;

        Duration conexionMinima = config.getMinimaConexion();
        int minCap = Integer.MAX_VALUE;

        for (int i = 0; i < vuelos.size(); i++) {
            Vuelo vuelo = vuelos.get(i);
            if (!vuelo.getOrigen().getCodigoOACI().equals(aeropuertoActual.getCodigoOACI())) return 0;

            LocalDateTime salida = vuelo.getSalidaUtc();
            Duration esperaRequerida = i == 0 ? Duration.ZERO : conexionMinima;
            if (salida.isBefore(instanteActual.plus(esperaRequerida))) return 0;

            int capAeropuerto = capacidadResidualIntervalo(aeropuertoActual, instanteActual, salida);
            minCap = Math.min(minCap, capAeropuerto);

            int capVuelo = capacidadResidualVuelo(vuelo);
            minCap = Math.min(minCap, capVuelo);

            aeropuertoActual = vuelo.getDestino();
            instanteActual = vuelo.getLlegadaUtc();
        }

        if (!aeropuertoActual.getCodigoOACI().equals(paquete.getDestinoOACI())) return 0;

        Vuelo ultimoVuelo = vuelos.get(vuelos.size() - 1);
        LocalDateTime finRecogida = ultimoVuelo.getLlegadaUtc().plus(config.getTiempoRecogidaDestino());
        int capDestino = capacidadResidualIntervalo(aeropuertoActual, ultimoVuelo.getLlegadaUtc(), finRecogida);
        minCap = Math.min(minCap, capDestino);

        return minCap == Integer.MAX_VALUE ? 0 : minCap;
    }

    private int capacidadResidualIntervalo(Aeropuerto aeropuerto, LocalDateTime inicioIncl, LocalDateTime finExcl) {
        if (!finExcl.isAfter(inicioIncl)) return Integer.MAX_VALUE;
        int min = Integer.MAX_VALUE;
        LocalDateTime hora = inicioIncl.truncatedTo(ChronoUnit.HOURS);
        while (hora.isBefore(finExcl)) {
            int actual = getOcupacionHora(aeropuerto.getCodigoOACI(), hora);
            int libre = aeropuerto.getCapacidadMaxima() - actual;
            min = Math.min(min, libre);
            hora = hora.plusHours(1);
        }
        return min;
    }

    private int capacidadResidualVuelo(Vuelo vuelo) {
        int actual = cargaPorVuelo.getOrDefault(vuelo.getId(), 0);
        return vuelo.getCapacidadCarga() - actual;
    }

    public void liberarRuta(
            Paquete paquete,
            Ruta ruta,
            LocalDateTime creacionUtc,
            Dataset datos,
            Config_Simulacion config,
            int cantidad
    ) {
        Aeropuerto aeropuertoActual = datos.getAeropuerto(paquete.getOrigenOACI());
        if (aeropuertoActual == null) {
            aeropuertoActual = datos.getAeropuerto(config.getAeropuertoHub());
        }
        if (aeropuertoActual == null) return;

        LocalDateTime instanteActual = creacionUtc;
        for (Vuelo vuelo : ruta.getVuelos()) {
            reservarIntervalo(aeropuertoActual, instanteActual, vuelo.getSalidaUtc(), -cantidad);
            reservarVuelo(vuelo, -cantidad);
            aeropuertoActual = vuelo.getDestino();
            instanteActual = vuelo.getLlegadaUtc();
        }
    }
}
