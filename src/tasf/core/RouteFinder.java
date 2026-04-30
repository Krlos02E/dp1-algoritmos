package tasf.core;

import tasf.model.Ruta;
import tasf.model.Vuelo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RouteFinder {
    private final Dataset datos;

    public RouteFinder(Dataset datos) {
        this.datos = datos;
    }

    public List<Ruta> buscarRutas(
            String origen,
            String destino,
            LocalDateTime disponibleDesdeUtc,
            int maxRutas,
            int maxEscalas,
            Duration minimaConexion,
            Duration horizonteBusqueda
    ) {
        List<Ruta> rutas = new ArrayList<>();
        ArrayDeque<Vuelo> actual = new ArrayDeque<>();
        Set<String> visitados = new HashSet<>();
        visitados.add(origen);

        explorar(
                origen,
                destino,
                disponibleDesdeUtc,
                disponibleDesdeUtc,
                maxEscalas,
                minimaConexion,
                horizonteBusqueda,
                visitados,
                actual,
                rutas,
                maxRutas * 4
        );

        rutas.sort(
            Comparator
                .comparing(Ruta::getLlegadaUtc)
                .thenComparingInt(Ruta::getCantidadSaltos)
        );
        if (rutas.size() > maxRutas) {
            return new ArrayList<>(rutas.subList(0, maxRutas));
        }
        return rutas;
    }

    private void explorar(
            String aeropuertoActual,
            String destino,
            LocalDateTime instanteActual,
            LocalDateTime inicioBusqueda,
            int maxEscalas,
            Duration minimaConexion,
            Duration horizonteBusqueda,
            Set<String> visitados,
            ArrayDeque<Vuelo> rutaActual,
            List<Ruta> rutas,
            int limiteInterno
    ) {
        if (rutas.size() >= limiteInterno) {
            return;
        }

        if (aeropuertoActual.equals(destino) && !rutaActual.isEmpty()) {
            rutas.add(new Ruta(new ArrayList<>(rutaActual)));
            return;
        }

        if (rutaActual.size() > maxEscalas) {
            return;
        }

        List<Vuelo> candidatos = datos.getVuelosDesde(aeropuertoActual);

        for (Vuelo vuelo : candidatos) {
            Duration esperaRequerida = rutaActual.isEmpty() ? Duration.ZERO : minimaConexion;
            if (vuelo.getSalidaUtc().isBefore(instanteActual.plus(esperaRequerida))) {
                continue;
            }
            if (vuelo.getSalidaUtc().isAfter(inicioBusqueda.plus(horizonteBusqueda))) {
                continue;
            }

            String siguiente = vuelo.getDestino().getCodigoOACI();
            if (visitados.contains(siguiente) && !siguiente.equals(destino)) {
                continue;
            }

            rutaActual.addLast(vuelo);
            boolean agregado = visitados.add(siguiente);

            explorar(
                    siguiente,
                    destino,
                    vuelo.getLlegadaUtc(),
                    inicioBusqueda,
                    maxEscalas,
                    minimaConexion,
                    horizonteBusqueda,
                    visitados,
                    rutaActual,
                    rutas,
                    limiteInterno
            );

            if (agregado) {
                visitados.remove(siguiente);
            }
            rutaActual.removeLast();
        }
        
    }
}
