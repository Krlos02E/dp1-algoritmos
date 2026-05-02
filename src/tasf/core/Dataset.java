package tasf.core;

import tasf.model.Aeropuerto;
import tasf.model.Paquete;
import tasf.model.Tramo;
import tasf.model.Vuelo;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Dataset {
    private final Map<String, Aeropuerto> aeropuertos;
    private final List<Vuelo> vuelos;
    private final List<Paquete> paquetes;
    private final Map<String, List<Vuelo>> vuelosPorOrigen;
    private final Map<String, Paquete> paquetesPorId;
    private final Map<String, Map<String, Integer>> cacheSaltos;
    private final Map<String, Set<String>> destinosPorOrigenYEscalas;

    public Dataset(Map<String, Aeropuerto> aeropuertos, List<Vuelo> vuelos, List<Paquete> paquetes) {
        this.aeropuertos = Collections.unmodifiableMap(new HashMap<>(aeropuertos));
        this.vuelos = Collections.unmodifiableList(new ArrayList<>(vuelos));
        this.paquetes = Collections.unmodifiableList(new ArrayList<>(paquetes));
        this.vuelosPorOrigen = new HashMap<>();
        this.paquetesPorId = new HashMap<>();
        this.cacheSaltos = new ConcurrentHashMap<>();

        for (Vuelo vuelo : vuelos) {
            vuelosPorOrigen.computeIfAbsent(vuelo.getOrigen().getCodigoOACI(), k -> new ArrayList<>()).add(vuelo);
        }
        for (List<Vuelo> porOrigen : vuelosPorOrigen.values()) {
            porOrigen.sort((a, b) -> a.getSalidaUtc().compareTo(b.getSalidaUtc()));
        }
        for (Paquete paquete : paquetes) {
            paquetesPorId.put(paquete.getId(), paquete);
        }

        destinosPorOrigenYEscalas = new HashMap<>();
        precalcularDestinosAlcanzables();
    }

    private void precalcularDestinosAlcanzables() {
        int maxEscalas = 3;
        Set<String> aeropuertos = vuelosPorOrigen.keySet();
        for (String origen : aeropuertos) {
            for (int escalas = 0; escalas <= maxEscalas; escalas++) {
                String key = origen + "|" + escalas;
                destinosPorOrigenYEscalas.put(key, encontrarDestinos(origen, escalas));
            }
        }
    }

    private Set<String> encontrarDestinos(String origen, int maxEscalas) {
        Set<String> alcanzables = new HashSet<>();
        Set<String> actuales = new HashSet<>();
        actuales.add(origen);

        for (int salto = 0; salto <= maxEscalas; salto++) {
            Set<String> siguientes = new HashSet<>();
            for (String actual : actuales) {
                List<Vuelo> vuelos = vuelosPorOrigen.get(actual);
                if (vuelos != null) {
                    for (Vuelo v : vuelos) {
                        String dest = v.getDestino().getCodigoOACI();
                        if (alcanzables.add(dest)) {
                            siguientes.add(dest);
                        }
                    }
                }
            }
            actuales = siguientes;
            if (actuales.isEmpty()) break;
        }
        return alcanzables;
    }

    public boolean puedeLlegarA(String origen, String destino, int maxEscalas) {
        String key = origen + "|" + maxEscalas;
        Set<String> alcanzables = destinosPorOrigenYEscalas.get(key);
        return alcanzables != null && alcanzables.contains(destino);
    }

    public Map<String, Aeropuerto> getAeropuertos() {
        return aeropuertos;
    }

    public Aeropuerto getAeropuerto(String codigoOACI) {
        return aeropuertos.get(codigoOACI);
    }

    public List<Vuelo> getVuelos() {
        return vuelos;
    }

    public List<Paquete> getPaquetes() {
        return paquetes;
    }

    public Paquete getPaquetePorId(String id) {
        return paquetesPorId.get(id);
    }

    public List<Vuelo> getVuelosDesde(String origenOACI) {
        return vuelosPorOrigen.getOrDefault(origenOACI, Collections.emptyList());
    }

    public List<Vuelo> getVuelosDesde(String origenOACI, LocalDateTime desdeUtc, Duration horizonteBusqueda) {
        List<Vuelo> lista = vuelosPorOrigen.get(origenOACI);
        if (lista == null || lista.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime finUtc = desdeUtc.plus(horizonteBusqueda);

        int inicio = buscarPrimerVueloDespues(lista, desdeUtc);
        int fin = buscarPrimerVueloDespues(lista, finUtc);

        if (inicio >= lista.size()) {
            return Collections.emptyList();
        }
        return lista.subList(inicio, Math.min(fin, lista.size()));
    }

    private int buscarPrimerVueloDespues(List<Vuelo> lista, LocalDateTime instante) {
        int bajo = 0;
        int alto = lista.size();
        while (bajo < alto) {
            int medio = (bajo + alto) >>> 1;
            if (lista.get(medio).getSalidaUtc().isBefore(instante)) {
                bajo = medio + 1;
            } else {
                alto = medio;
            }
        }
        return bajo;
    }

    public Set<Tramo> getTodosLosTramos() {
        Set<Tramo> tramos = new HashSet<>();
        for (Vuelo vuelo : vuelos) {
            tramos.add(vuelo.getTramo());
        }
        return tramos;
    }

    public int distanciaEnSaltos(String origen, String destino) {
        if (origen.equals(destino)) {
            return 0;
        }

        Map<String, Integer> cacheOrigen = cacheSaltos.computeIfAbsent(origen, k -> new ConcurrentHashMap<>());
        Integer cached = cacheOrigen.get(destino);
        if (cached != null) {
            return cached;
        }

        ArrayDeque<String> cola = new ArrayDeque<>();
        Map<String, Integer> dist = new HashMap<>();
        cola.add(origen);
        dist.put(origen, 0);

        while (!cola.isEmpty()) {
            String actual = cola.removeFirst();
            int d = dist.get(actual);

            for (Vuelo vuelo : getVuelosDesde(actual)) {
                String sig = vuelo.getDestino().getCodigoOACI();
                if (!dist.containsKey(sig)) {
                    dist.put(sig, d + 1);
                    cola.add(sig);
                    if (sig.equals(destino)) {
                        cacheOrigen.put(destino, d + 1);
                        return d + 1;
                    }
                }
            }
        }

        cacheOrigen.put(destino, Integer.MAX_VALUE);
        return Integer.MAX_VALUE;
    }
}
