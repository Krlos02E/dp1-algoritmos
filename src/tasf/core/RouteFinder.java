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
    private static final long TIMEOUT_NS = 100_000_000L; // 100ms por par OD

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

        List<Vuelo> candidatosIniciales = datos.getVuelosDesde(origen, disponibleDesdeUtc, horizonteBusqueda);
        long deadline = System.nanoTime() + TIMEOUT_NS;

        explorar(
                origen, destino, disponibleDesdeUtc, disponibleDesdeUtc,
                maxEscalas, minimaConexion, horizonteBusqueda,
                visitados, actual, rutas, maxRutas * 8,
                candidatosIniciales, deadline
        );

        rutas.sort(
            Comparator.comparing(Ruta::getLlegadaUtc).thenComparingInt(Ruta::getCantidadSaltos)
        );
        if (rutas.size() > maxRutas) {
            return new ArrayList<>(rutas.subList(0, maxRutas));
        }
        return rutas;
    }

    private void explorar(
            String aeropuertoActual, String destino,
            LocalDateTime instanteActual, LocalDateTime inicioBusqueda,
            int maxEscalas, Duration minimaConexion, Duration horizonteBusqueda,
            Set<String> visitados, ArrayDeque<Vuelo> rutaActual,
            List<Ruta> rutas, int limiteInterno,
            List<Vuelo> candidatosIniciales, long deadline
    ) {
        if (rutas.size() >= limiteInterno) return;
        if (System.nanoTime() >= deadline) return;
        if (aeropuertoActual.equals(destino) && !rutaActual.isEmpty()) {
            rutas.add(new Ruta(new ArrayList<>(rutaActual)));
            return;
        }
        if (rutaActual.size() >= maxEscalas) return;

        List<Vuelo> candidatos;
        if (!rutaActual.isEmpty()) {
            LocalDateTime desde = instanteActual.plus(minimaConexion);
            candidatos = datos.getVuelosDesde(aeropuertoActual, desde, horizonteBusqueda);
        } else {
            candidatos = candidatosIniciales;
        }

        LocalDateTime finVentana = inicioBusqueda.plus(horizonteBusqueda);
        for (Vuelo vuelo : candidatos) {
            if (rutas.size() >= limiteInterno) break;
            if (System.nanoTime() >= deadline) break;
            if (vuelo.getSalidaUtc().isAfter(finVentana)) break;

            String siguiente = vuelo.getDestino().getCodigoOACI();
            if (visitados.contains(siguiente) && !siguiente.equals(destino)) continue;

            int escalasRestantes = maxEscalas - rutaActual.size();
            if (!siguiente.equals(destino) && escalasRestantes > 1 && !datos.puedeLlegarA(siguiente, destino, escalasRestantes - 1)) {
                continue;
            }

            rutaActual.addLast(vuelo);
            boolean agregado = visitados.add(siguiente);

            explorar(
                    siguiente, destino, vuelo.getLlegadaUtc(), inicioBusqueda,
                    maxEscalas, minimaConexion, horizonteBusqueda,
                    visitados, rutaActual, rutas, limiteInterno,
                    null, deadline
            );

            if (agregado) visitados.remove(siguiente);
            rutaActual.removeLast();
        }
    }
}
