package tasf.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class Aeropuerto {
    private final String codigoOACI;
    private final Continente continente;
    private final int gmtOffsetMinutos;
    private final int capacidadMaxima;

    public Aeropuerto(String codigoOACI, Continente continente, int gmtOffsetMinutos, int capacidadMaxima) {
        this.codigoOACI = codigoOACI;
        this.continente = continente;
        this.gmtOffsetMinutos = gmtOffsetMinutos;
        this.capacidadMaxima = capacidadMaxima;
    }

    public String getCodigoOACI() {
        return codigoOACI;
    }

    public Continente getContinente() {
        return continente;
    }

    public int getGmtOffsetMinutos() {
        return gmtOffsetMinutos;
    }

    public int getCapacidadMaxima() {
        return capacidadMaxima;
    }

    public LocalDateTime convertirLocalAUTC(LocalDate fechaLocal, LocalTime horaLocal) {
        LocalDateTime instanteLocal = LocalDateTime.of(fechaLocal, horaLocal);
        return instanteLocal.minusMinutes(gmtOffsetMinutos);
    }
}