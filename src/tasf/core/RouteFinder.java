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
    private static final long TIMEOUT_NS = 500_000_000L; // 500ms por par OD

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

        List<LocalDateTime> inicios = new ArrayList<>();
        inicios.add(disponibleDesdeUtc);
        // Iniciar búsquedas más cercanas al deadline para encontrar rutas rápidas
        Duration plazoMaximo = Duration.ofHours(48); // Asumir max 48h
        for (int i = 1; i <= 5; i++) {
            LocalDateTime tryInicio = disponibleDesdeUtc.plusHours(i * 6); // Cada 6 horas
            if (!tryInicio.isAfter(disponibleDesdeUtc.plus(plazoMaximo))) {
                inicios.add(tryInicio);
            }
        }
        //También buscar desde 1h, 2h, 3h después (buscar rutas tempranas)
        for (int i = 1; i <= 3; i++) {
            LocalDateTime tryInicio = disponibleDesdeUtc.plusHours(i);
            if (!tryInicio.isAfter(disponibleDesdeUtc.plus(plazoMaximo))) {
                inicios.add(tryInicio);
            }
        }

        long deadline = System.nanoTime() + TIMEOUT_NS;
        int limiteInterno = maxRutas * 6;

        for (LocalDateTime inicio : inicios) {
            if (System.nanoTime() >= deadline) break;
            if (rutas.size() >= limiteInterno) break;

            visitados.clear();
            visitados.add(origen);
            actual.clear();

            List<Vuelo> candidatosIniciales = datos.getVuelosDesde(origen, inicio, horizonteBusqueda);
            explorar(
                    origen, destino, inicio, inicio,
                    maxEscalas, minimaConexion, horizonteBusqueda,
                    visitados, actual, rutas, limiteInterno,
                    candidatosIniciales, deadline
            );
        }

        rutas.sort(Comparator.comparing(Ruta::getLlegadaUtc)
                .thenComparingInt((Ruta r) -> r.getCantidadSaltos() - 1)
                .thenComparingDouble((Ruta r) -> calcularEsperaTotal(r, disponibleDesdeUtc)));
        
        List<Ruta> filtradas = new ArrayList<>();
        Set<String> firmasUsadas = new HashSet<>();
        
        for (Ruta r : rutas) {
            if (filtradas.size() >= maxRutas) break;
            
            List<Vuelo> vuelos = r.getVuelos();
            StringBuilder firma = new StringBuilder();
            for (Vuelo v : vuelos) {
                firma.append(v.getOrigen().getCodigoOACI()).append("-");
            }
            firma.append(r.getLlegadaUtc());
            
            String key = firma.toString();
            if (!firmasUsadas.contains(key) || filtradas.size() < maxRutas / 3) {
                filtradas.add(r);
                firmasUsadas.add(key);
            }
        }
        
        return filtradas;
    }

    private double calcularEsperaTotal(Ruta ruta, LocalDateTime creacion) {
        double totalMinutosEspera = 0;
        LocalDateTime instante = creacion;
        for (Vuelo v : ruta.getVuelos()) {
            long espera = Duration.between(instante, v.getSalidaUtc()).toMinutes();
            totalMinutosEspera += Math.max(0, espera);
            instante = v.getLlegadaUtc();
        }
        return totalMinutosEspera;
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
