package tasf.core;

import tasf.model.Ruta;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AsignacionPaquete {
    private final List<RutaConCantidad> rutas;

    public AsignacionPaquete(List<RutaConCantidad> rutas) {
        this.rutas = new ArrayList<>(rutas);
    }

    public AsignacionPaquete(Ruta ruta, int cantidad) {
        this.rutas = new ArrayList<>();
        this.rutas.add(new RutaConCantidad(ruta, cantidad));
    }

    public List<RutaConCantidad> getRutas() {
        return Collections.unmodifiableList(rutas);
    }

    public int cantidadAsignada() {
        int total = 0;
        for (RutaConCantidad rc : rutas) {
            total += rc.getCantidad();
        }
        return total;
    }

    public LocalDateTime getUltimaLlegada() {
        LocalDateTime ultima = null;
        for (RutaConCantidad rc : rutas) {
            LocalDateTime llegada = rc.getRuta().getLlegadaUtc();
            if (ultima == null || llegada.isAfter(ultima)) {
                ultima = llegada;
            }
        }
        return ultima;
    }

    public LocalDateTime getPrimeraLlegada() {
        LocalDateTime primera = null;
        for (RutaConCantidad rc : rutas) {
            LocalDateTime llegada = rc.getRuta().getLlegadaUtc();
            if (primera == null || llegada.isBefore(primera)) {
                primera = llegada;
            }
        }
        return primera;
    }

    public Ruta getMejorRuta() {
        if (rutas.isEmpty()) return null;
        RutaConCantidad mayor = rutas.get(0);
        for (int i = 1; i < rutas.size(); i++) {
            if (rutas.get(i).getCantidad() > mayor.getCantidad()) {
                mayor = rutas.get(i);
            }
        }
        return mayor.getRuta();
    }

    public int cantidadRutas() {
        return rutas.size();
    }

    public boolean isEmpty() {
        return rutas.isEmpty();
    }

    public void agregarRuta(Ruta ruta, int cantidad) {
        rutas.add(new RutaConCantidad(ruta, cantidad));
    }

    public void removerRuta(int index) {
        if (index >= 0 && index < rutas.size()) {
            rutas.remove(index);
        }
    }

    public AsignacionPaquete copia() {
        List<RutaConCantidad> copiaRutas = new ArrayList<>();
        for (RutaConCantidad rc : rutas) {
            copiaRutas.add(new RutaConCantidad(rc.getRuta(), rc.getCantidad()));
        }
        return new AsignacionPaquete(copiaRutas);
    }

    public double getHorasTotalesDesde(LocalDateTime inicioUtc) {
        double maxHoras = 0;
        for (RutaConCantidad rc : rutas) {
            double horas = rc.getRuta().getHorasTotalesDesde(inicioUtc);
            if (horas > maxHoras) {
                maxHoras = horas;
            }
        }
        return maxHoras;
    }
}
