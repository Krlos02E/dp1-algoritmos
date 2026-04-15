package tasf.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class Paquete {
    private final String id;
    private final String origenOACI;
    private final LocalDate fecha;
    private final LocalTime hora;
    private final String destinoOACI;
    private final int cantidad;
    private final String referencia;

    public Paquete(String id, String origenOACI, LocalDate fecha, LocalTime hora, String destinoOACI, int cantidad, String referencia) {
        this.id = id;
        this.origenOACI = origenOACI;
        this.fecha = fecha;
        this.hora = hora;
        this.destinoOACI = destinoOACI;
        this.cantidad = cantidad;
        this.referencia = referencia;
    }

    public String getId() {
        return id;
    }

    public String getOrigenOACI() {
        return origenOACI;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public LocalTime getHora() {
        return hora;
    }

    public String getDestinoOACI() {
        return destinoOACI;
    }

    public int getCantidad() {
        return cantidad;
    }

    public String getReferencia() {
        return referencia;
    }

    public LocalDateTime getInstanteCreacionUtc(Aeropuerto aeropuertoOrigen) {
        return aeropuertoOrigen.convertirLocalAUTC(fecha, hora);
    }

    public static Paquete parse(String raw) {
        return parse(raw, "SKBO");
    }

    public static Paquete parse(String raw, String origenOACI) {
        String[] parts = raw.trim().split("-");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Formato de paquete invalido: " + raw);
        }

        String id = parts[0];
        LocalDate fecha = LocalDate.of(
                Integer.parseInt(parts[1].substring(0, 4)),
                Integer.parseInt(parts[1].substring(4, 6)),
                Integer.parseInt(parts[1].substring(6, 8))
        );

        LocalTime hora;
        String destino;
        int cantidad;
        String referencia = "";

        if (parts[2].contains(":")) {
            hora = LocalTime.parse(parts[2]);
            destino = parts[3];
            cantidad = Integer.parseInt(parts[4]);
            if (parts.length > 5) {
                referencia = parts[5];
            }
        } else {
            if (parts.length < 6) {
                throw new IllegalArgumentException("Formato extendido invalido: " + raw);
            }
            int hh = Integer.parseInt(parts[2]);
            int mm = Integer.parseInt(parts[3]);
            hora = LocalTime.of(hh, mm);
            destino = parts[4];
            cantidad = Integer.parseInt(parts[5]);
            if (parts.length > 6) {
                referencia = parts[6];
            }
        }

        return new Paquete(id, origenOACI, fecha, hora, destino, cantidad, referencia);
    }
}
