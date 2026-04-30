package tasf.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Ruta {
    private final List<Vuelo> vuelos;

    public Ruta(List<Vuelo> vuelos) {
        this.vuelos = Collections.unmodifiableList(new ArrayList<>(vuelos));
    }

    public List<Vuelo> getVuelos() {
        return vuelos;
    }

    public boolean isVacia() {
        return vuelos.isEmpty();
    }

    public LocalDateTime getSalidaUtc() {
        return vuelos.get(0).getSalidaUtc();
    }

    public LocalDateTime getLlegadaUtc() {
        return vuelos.get(vuelos.size() - 1).getLlegadaUtc();
    }

    public String getOrigenOACI() {
        return vuelos.get(0).getOrigen().getCodigoOACI();
    }

    public String getDestinoOACI() {
        return vuelos.get(vuelos.size() - 1).getDestino().getCodigoOACI();
    }

    public int getCantidadSaltos() {
        return vuelos.size();
    }

    public double getHorasTotalesDesde(LocalDateTime inicioUtc) {
        return Duration.between(inicioUtc, getLlegadaUtc()).toMinutes() / 60.0;
    }

    public Set<Tramo> getTramos() {
        Set<Tramo> tramos = new HashSet<>();
        for (Vuelo vuelo : vuelos) {
            tramos.add(vuelo.getTramo());
        }
        return tramos;
    }
}
