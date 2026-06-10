package tasf.core;

import tasf.model.Ruta;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Solucion {
    private final Map<String, Ruta> rutasAsignadas;
    private final Map<String, AsignacionPaquete> asignacionesSplit;
    private final Set<String> paquetesNoAsignados;
    private final Map<String, Double> metricas;
    private int maletasFueraDePlazo;
    private int eventosColapso;
    private double costoTotal;
    private int maletasAsignadas;

    public Solucion(String estrategia) {
        this.rutasAsignadas = new HashMap<>();
        this.asignacionesSplit = new HashMap<>();
        this.paquetesNoAsignados = new HashSet<>();
        this.metricas = new HashMap<>();
        this.maletasAsignadas = 0;
    }

    public Map<String, Ruta> getRutasAsignadas() {
        return Collections.unmodifiableMap(rutasAsignadas);
    }

    public Map<String, AsignacionPaquete> getAsignacionesSplit() {
        return Collections.unmodifiableMap(asignacionesSplit);
    }

    public Set<String> getPaquetesNoAsignados() {
        return Collections.unmodifiableSet(paquetesNoAsignados);
    }

    public int getMaletasFueraDePlazo() {
        return maletasFueraDePlazo;
    }

    public int getEventosColapso() {
        return eventosColapso;
    }

    public double getCostoTotal() {
        return costoTotal;
    }

    public int getMaletasAsignadas() {
        return maletasAsignadas;
    }

    public Map<String, Double> getMetricas() {
        return Collections.unmodifiableMap(metricas);
    }

    public void asignar(String paqueteId, Ruta ruta, boolean fueraDePlazo) {
        rutasAsignadas.put(paqueteId, ruta);
        paquetesNoAsignados.remove(paqueteId);
        if (fueraDePlazo) {
            maletasFueraDePlazo++;
        }
    }

    public void asignarSplit(String paqueteId, AsignacionPaquete asignacion, boolean fueraDePlazo, int cantidadAsignada) {
        asignacionesSplit.put(paqueteId, asignacion);
        rutasAsignadas.put(paqueteId, asignacion.getMejorRuta());
        paquetesNoAsignados.remove(paqueteId);
        maletasAsignadas += cantidadAsignada;
        if (fueraDePlazo) {
            maletasFueraDePlazo += cantidadAsignada;
        }
    }

    public void marcarNoAsignado(String paqueteId, boolean porColapso) {
        rutasAsignadas.remove(paqueteId);
        asignacionesSplit.remove(paqueteId);
        paquetesNoAsignados.add(paqueteId);
        if (porColapso) {
            eventosColapso++;
        }
    }

    public void setCostoTotal(double costoTotal) {
        this.costoTotal = costoTotal;
    }

    public void setMetrica(String nombre, double valor) {
        metricas.put(nombre, valor);
    }
}
