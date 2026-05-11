package tasf.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class Vuelo {
    private final String id;
    private final Aeropuerto origen;
    private final Aeropuerto destino;
    private final LocalDate fecha;
    private final LocalTime horaSalidaLocal;
    private final LocalTime horaLlegadaLocal;
    private final int capacidadCarga;
    private final LocalDateTime salidaUtc;
    private final LocalDateTime llegadaUtc;
    private final Tramo tramo;

    public Vuelo(
            String id,
            Aeropuerto origen,
            Aeropuerto destino,
            LocalDate fecha,
            LocalTime horaSalidaLocal,
            LocalTime horaLlegadaLocal,
            int capacidadCarga
    ) {
        this.id = id;
        this.origen = origen;
        this.destino = destino;
        this.fecha = fecha;
        this.horaSalidaLocal = horaSalidaLocal;
        this.horaLlegadaLocal = horaLlegadaLocal;
        this.capacidadCarga = capacidadCarga;

        LocalDateTime salidaLocalDt = LocalDateTime.of(fecha, horaSalidaLocal);
        LocalDateTime llegadaLocalDt = LocalDateTime.of(fecha, horaLlegadaLocal);
        if (!llegadaLocalDt.isAfter(salidaLocalDt)) {
            llegadaLocalDt = llegadaLocalDt.plusDays(1);
        }

        LocalDateTime salidaUtcTmp = origen.convertirLocalAUTC(salidaLocalDt.toLocalDate(), salidaLocalDt.toLocalTime());
        LocalDateTime llegadaUtcTmp = destino.convertirLocalAUTC(llegadaLocalDt.toLocalDate(), llegadaLocalDt.toLocalTime());

        while (!llegadaUtcTmp.isAfter(salidaUtcTmp)) {
            llegadaLocalDt = llegadaLocalDt.plusDays(1);
            llegadaUtcTmp = destino.convertirLocalAUTC(llegadaLocalDt.toLocalDate(), llegadaLocalDt.toLocalTime());
        }

        this.salidaUtc = salidaUtcTmp;
        this.llegadaUtc = llegadaUtcTmp;
        this.tramo = new Tramo(origen.getCodigoOACI(), destino.getCodigoOACI());
    }

    public String getId() {
        return id;
    }

    public Aeropuerto getOrigen() {
        return origen;
    }

    public Aeropuerto getDestino() {
        return destino;
    }

    public int getCapacidadCarga() {
        return capacidadCarga;
    }

    public LocalDateTime getSalidaUtc() {
        return salidaUtc;
    }

    public LocalDateTime getLlegadaUtc() {
        return llegadaUtc;
    }

    public Tramo getTramo() {
        return tramo;
    }
}