package tasf.tests;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.RouteFinder;
import tasf.core.PlanificacionUtils;
import tasf.core.EstadoOperacional;
import tasf.io.DatasetTextoLoader;
import tasf.model.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;

public class DiagnosticoCapacidad {
    public static void main(String[] args) {
        try {
            int diasVuelos = 50;
            LocalDate fechaInicio = LocalDate.parse("2028-11-01");

            System.out.println("[DIAG] Cargando datos...");
            Path dataDir = Paths.get("data/input");
            Dataset datos = DatasetTextoLoader.cargarDataset(dataDir, fechaInicio, diasVuelos, 0, null);
            System.out.printf("[DIAG] aeropuertos=%d vuelos=%d paquetes=%d%n",
                    datos.getAeropuertos().size(), datos.getVuelos().size(), datos.getPaquetes().size());

            // Config
            Config_Simulacion config = new Config_Simulacion();
            config.setAeropuertoHub("SPIM");
            config.setHorizonteBusqueda(Duration.ofHours(72));
            config.setMaxRutasPorPaquete(25);
            config.setMaxEscalas(2);

            // Tomar un subconjunto manejable: primeros 4430 paquetes por creacion
            int cantidad = 4430;
            List<Paquete> todos = new ArrayList<>(datos.getPaquetes());
            todos.sort((a, b) -> PlanificacionUtils.getCreacionUtc(a, datos, config)
                    .compareTo(PlanificacionUtils.getCreacionUtc(b, datos, config)));
            List<Paquete> sub = todos.subList(0, Math.min(cantidad, todos.size()));
            Dataset datosSub = new Dataset(datos.getAeropuertos(), datos.getVuelos(), sub);

            System.out.printf("[DIAG] Usando %d paquetes%n", datosSub.getPaquetes().size());

            // Verificar fechas de paquetes
            LocalDateTime primera = PlanificacionUtils.getCreacionUtc(sub.get(0), datosSub, config);
            LocalDateTime ultima = PlanificacionUtils.getCreacionUtc(sub.get(sub.size()-1), datosSub, config);
            System.out.printf("[DIAG] Rango creacion: %s .. %s%n", primera, ultima);

            // Verificar fechas de vuelos
            LocalDateTime primerVuelo = datos.getVuelos().get(0).getSalidaUtc();
            LocalDateTime ultimoVuelo = datos.getVuelos().get(datos.getVuelos().size()-1).getSalidaUtc();
            System.out.printf("[DIAG] Rango vuelos: %s .. %s%n", primerVuelo, ultimoVuelo);

            // Configurar ventana de simulacion
            config.setFinSimulacionUtcExclusivo(ultimoVuelo.plusDays(1));

            // Construir candidatos
            System.out.println("\n[DIAG] Construyendo candidatos...");
            RouteFinder finder = new RouteFinder(datosSub);
            Map<String, List<Ruta>> candidatos = PlanificacionUtils.construirCandidatosRutas(datosSub, config, finder);

            int totalRutas = 0;
            int sinRutas = 0;
            for (Paquete p : sub) {
                List<Ruta> c = candidatos.getOrDefault(p.getId(), List.of());
                if (c.isEmpty()) sinRutas++;
                else totalRutas += c.size();
            }
            System.out.printf("[DIAG] candidatos: conRutas=%d, sinRutas=%d, avg=%.1f%n",
                    cantidad - sinRutas, sinRutas, sinRutas < cantidad ? (double) totalRutas / (cantidad - sinRutas) : 0);

            // Greedy: primera ruta factible, orden por restriccion
            int g3 = greedySimple(datosSub, config, candidatos, (a, b) -> {
                int na = candidatos.getOrDefault(a.getId(), List.of()).size();
                int nb = candidatos.getOrDefault(b.getId(), List.of()).size();
                if (na != nb) return Integer.compare(na, nb);
                return PlanificacionUtils.getCreacionUtc(a, datosSub, config).compareTo(PlanificacionUtils.getCreacionUtc(b, datosSub, config));
            });
            System.out.printf("[DIAG] Greedy-por-restriccion: %d/%d%n", g3, cantidad);

            // Greedy: por fecha
            int g2 = greedySimple(datosSub, config, candidatos, (a, b) ->
                    PlanificacionUtils.getCreacionUtc(a, datosSub, config).compareTo(PlanificacionUtils.getCreacionUtc(b, datosSub, config)));
            System.out.printf("[DIAG] Greedy-por-fecha: %d/%d%n", g2, cantidad);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static int greedySimple(Dataset datos, Config_Simulacion config, Map<String, List<Ruta>> candidatos, Comparator<Paquete> orden) {
        EstadoOperacional estado = new EstadoOperacional();
        List<Paquete> paquetes = new ArrayList<>(datos.getPaquetes());
        paquetes.sort(orden);
        int count = 0;
        for (Paquete p : paquetes) {
            LocalDateTime creacion = PlanificacionUtils.getCreacionUtc(p, datos, config);
            for (Ruta r : candidatos.getOrDefault(p.getId(), List.of())) {
                if (PlanificacionUtils.estaFueraDeVentanaSimulacion(r, config)) continue;
                EstadoOperacional prueba = estado.copia();
                if (prueba.reservarRutaSiFactible(p, r, creacion, datos, config)) {
                    estado.reservarRutaSiFactible(p, r, creacion, datos, config);
                    count++;
                    break;
                }
            }
        }
        return count;
    }
}
