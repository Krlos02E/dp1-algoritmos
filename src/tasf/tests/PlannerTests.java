package tasf.tests;

import tasf.config.Config_Simulacion;
import tasf.core.Dataset;
import tasf.core.PlanificacionUtils;
import tasf.core.RouteFinder;
import tasf.core.Solucion;
import tasf.io.PlanesVueloParser;
import tasf.model.Aeropuerto;
import tasf.model.Continente;
import tasf.model.Paquete;
import tasf.model.Ruta;
import tasf.model.Vuelo;
import tasf.strategy.PlanificadorStrategy;
import tasf.strategy.aco.ACO_Strategy;
import tasf.strategy.alns.ALNS_Strategy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlannerTests {
    public static void main(String[] args) {
        PlannerTests tests = new PlannerTests();
        tests.testParserPaqueteExtendido();
        tests.testConversionUtcYConexionFactible();
        tests.testRestriccionCapacidadAlmacen();
        tests.testALNSActualizaPesos();
        tests.testACOActualizaFeromonas();
        System.out.println("Todas las pruebas pasaron correctamente.");
    }

    void testParserPaqueteExtendido() {
        Paquete p = Paquete.parse("000000001-20260102-00-47-SUAA-002-0032535");
        assertEquals("000000001", p.getId(), "ID invalido");
        assertEquals(LocalDate.of(2026, 1, 2), p.getFecha(), "Fecha invalida");
        assertEquals(LocalTime.of(0, 47), p.getHora(), "Hora invalida");
        assertEquals("SUAA", p.getDestinoOACI(), "Destino invalido");
        assertEquals(2, p.getCantidad(), "Cantidad invalida");
        assertEquals("0032535", p.getReferencia(), "Referencia invalida");
    }

    void testConversionUtcYConexionFactible() {
        Aeropuerto origen = new Aeropuerto("SKBO", Continente.AMERICA, -300, 1000);
        LocalDateTime utc = origen.convertirLocalAUTC(LocalDate.of(2026, 1, 2), LocalTime.of(10, 0));
        assertEquals(LocalDateTime.of(2026, 1, 2, 15, 0), utc, "Conversion UTC invalida");

        Dataset datos = crearDatasetBase(100);
        Config_Simulacion config = crearConfigBase();
        RouteFinder finder = new RouteFinder(datos);
        Paquete p = datos.getPaquetes().get(0);

        List<Ruta> rutas = finder.buscarRutas(
                "SKBO",
                p.getDestinoOACI(),
                PlanificacionUtils.getCreacionUtc(p, datos, config),
                5,
                3,
                Duration.ofMinutes(30),
                Duration.ofHours(48)
        );

        assertTrue(!rutas.isEmpty(), "No se encontro conexion factible en UTC");
    }

    void testRestriccionCapacidadAlmacen() {
        Dataset datos = crearDatasetBase(1);
        Config_Simulacion config = crearConfigBase();
        config.setIteracionesALNS(30);

        PlanificadorStrategy alns = new ALNS_Strategy(7L);
        Solucion solucion = alns.planificar(datos, config);

        assertTrue(
                solucion.getPaquetesNoAsignados().size() >= 1,
                "Se esperaba al menos un no asignado por restriccion de capacidad"
        );
    }

    void testALNSActualizaPesos() {
        Dataset datos = crearDatasetBase(100);
        Config_Simulacion config = crearConfigBase();
        config.setIteracionesALNS(50);
        config.setVentanaActualizacionPesos(10);

        PlanificadorStrategy alns = new ALNS_Strategy(11L);
        Solucion solucion = alns.planificar(datos, config);

        double w1 = solucion.getMetricas().getOrDefault("pesoRupturaRandom", 1.0);
        double w2 = solucion.getMetricas().getOrDefault("pesoRupturaWorstDelay", 1.0);
        double w3 = solucion.getMetricas().getOrDefault("pesoReparacionGreedy", 1.0);
        double w4 = solucion.getMetricas().getOrDefault("pesoReparacionRegret", 1.0);

        assertTrue(
            solucion.getMetricas().containsKey("pesoRupturaRandom")
                && solucion.getMetricas().containsKey("pesoRupturaWorstDelay")
                && solucion.getMetricas().containsKey("pesoReparacionGreedy")
                && solucion.getMetricas().containsKey("pesoReparacionRegret"),
            "No se reportaron pesos ALNS"
        );

        assertTrue(
            w1 > 0.0 && w2 > 0.0 && w3 > 0.0 && w4 > 0.0,
            "Los pesos ALNS deben ser positivos"
        );
    }

    void testACOActualizaFeromonas() {
        Dataset datos = crearDatasetBase(100);
        Config_Simulacion config = crearConfigBase();
        config.setIteracionesACO(20);
        config.setHormigasACO(8);

        PlanificadorStrategy aco = new ACO_Strategy(13L);
        Solucion solucion = aco.planificar(datos, config);

        double maxFer = solucion.getMetricas().getOrDefault("feromonaMaxima", 0.0);
        assertTrue(maxFer > 1.0, "No hubo actualizacion de feromonas en ACO");
    }

    private Dataset crearDatasetBase(int capacidadHub) {
        Map<String, Aeropuerto> aeropuertos = new HashMap<>();
        aeropuertos.put("SKBO", new Aeropuerto("SKBO", Continente.AMERICA, -300, capacidadHub));
        aeropuertos.put("SEQM", new Aeropuerto("SEQM", Continente.AMERICA, -300, 100));
        aeropuertos.put("SVMI", new Aeropuerto("SVMI", Continente.AMERICA, -240, 100));
        aeropuertos.put("EHAM", new Aeropuerto("EHAM", Continente.EUROPA, 60, 100));

        List<Vuelo> vuelos = PlanesVueloParser.parseLineas(
                List.of(
                        "SKBO-SEQM-03:00-04:00-2",
                        "SEQM-SVMI-05:00-06:00-2",
                        "SKBO-SVMI-04:10-06:10-2",
                        "SKBO-EHAM-01:00-12:00-1"
                ),
                LocalDate.of(2026, 1, 2),
                1,
                aeropuertos
        );

        List<Paquete> paquetes = List.of(
                Paquete.parse("000000001-20260102-00-10-SVMI-001-000001"),
                Paquete.parse("000000002-20260102-00-20-SVMI-001-000002"),
                Paquete.parse("000000003-20260102-01-00-EHAM-001-000003")
        );

        return new Dataset(aeropuertos, vuelos, paquetes);
    }

    private Config_Simulacion crearConfigBase() {
        Config_Simulacion config = new Config_Simulacion();
        config.setAeropuertoHub("SKBO");
        config.setPlazoMismoContinente(Duration.ofHours(24));
        config.setPlazoIntercontinental(Duration.ofHours(48));
        config.setMinimaConexion(Duration.ofMinutes(20));
        config.setMaxEscalas(3);
        config.setMaxRutasPorPaquete(8);
        return config;
    }

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected != null && expected.equals(actual)) {
            return;
        }
        throw new AssertionError(message + " | esperado=" + expected + ", actual=" + actual);
    }
}
