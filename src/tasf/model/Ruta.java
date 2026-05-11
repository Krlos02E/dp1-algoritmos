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
    private final Set<Tramo> tramos;

    public Ruta(List<Vuelo> vuelos) {
        this.vuelos = Collections.unmodifiableList(new ArrayList<>(vuelos));
        Set<Tramo> tmp = new HashSet<>();
        for (Vuelo vuelo : this.vuelos) {
            tmp.add(vuelo.getTramo());
        }
        this.tramos = Collections.unmodifiableSet(tmp);
    }

    public List<Vuelo> getVuelos() {
        return vuelos;
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
        return tramos;
    }
}
