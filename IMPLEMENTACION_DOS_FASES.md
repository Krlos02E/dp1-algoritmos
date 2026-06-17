# Solución de dos fases: planificación de rutas + validación determinística

## Resumen de implementación

La solución quedó dividida en dos fases coordinadas:

### Fase 1: planificación de rutas
- Algoritmos: ALNS (se ejecuta uno por invocación).
- Salida: `Map<String, Ruta>` con una ruta seleccionada por paquete.
- Contrato: `PlanificadorRutasStrategy`.
- Implementadores: `ALNS_RutasPlanner`.

### Fase 2: validación determinística
- Algoritmo: validación operacional sobre la ruta ya elegida.
- Entrada: `Map<String, Ruta>` producido por la Fase 1.
- Salida: `Map<String, Ruta>` aceptada por el estado operacional.
- Clase: `MinCostFlowAssigner`.

### Orquestador
- Clase: `TwoPhaseOrchestrator`.
- Función: coordina ambas fases y retorna una `Solucion` evaluada.

La evaluación final usa la función global de `PlanificacionUtils` y no una puntuación local por paquete.

---

## Archivos del Proyecto

```
src/tasf/
├── app/
│   └── Main.java                              (PUNTO DE ENTRADA + CLI)
├── experiments/
│   └── StandardExperimentPipeline.java         (PIPELINE DE EXPERIMENTACIÓN)
├── strategy/
│   ├── PlanificadorRutasStrategy.java           (INTERFAZ FASE 1)
│   ├── PlanificadorStrategy.java                (INTERFAZ INTERNA METAHEURÍSTICA)
│   ├── TwoPhaseOrchestrator.java               (ORQUESTADOR)
│   ├── alns/
│   │   ├── ALNS_RutasPlanner.java              (WRAPPER ALNS)
│   │   └── ALNS_Strategy.java                  (LÓGICA ALNS)
│   └── flow/
│       ├── Asignador.java                       (INTERFAZ FASE 2)
│       ├── MinCostFlowAsignador.java           (ADAPTADOR FASE 2)
│       └── MinCostFlowAssigner.java            (ASIGNADOR DE VUELOS)
├── core/
│   ├── Dataset.java                            (MODELO DE DATOS)
│   ├── EstadoOperacional.java                  (ESTADO DE CAPACIDAD)
│   ├── PlanificacionUtils.java                 (UTILIDADES + EVALUACIÓN)
│   ├── RouteFinder.java                        (BÚSQUEDA DE RUTAS)
│   ├── DistribucionEnviosPorDia.java           (DISTRIBUCIÓN POR DÍA)
│   ├── CapacidadDiariaCalculadora.java         (CAPACIDAD DIARIA)
│   ├── ColapsoDetector.java                    (DETECCIÓN DE COLAPSO)
│   └── Solucion.java                           (MODELO DE SOLUCIÓN)
├── model/
│   ├── Aeropuerto.java                         (MODELO AEROPUERTO)
│   ├── Paquete.java                            (MODELO PAQUETE)
│   ├── Ruta.java                               (MODELO RUTA)
│   ├── Vuelo.java                              (MODELO VUELO)
│   └── Tramo.java                              (MODELO TRAMO)
├── io/
│   └── DatasetTextoLoader.java                 (CARGA DE ARCHIVOS)
└── config/
    └── Config_Simulacion.java                  (CONFIGURACIÓN)
```

---

## Uso básico

### Ejemplo 1: flujo completo con ALNS

```java
PlanificadorRutasStrategy planificador = new ALNS_RutasPlanner(semilla);
TwoPhaseOrchestrator orchestrator = new TwoPhaseOrchestrator(planificador);
Solucion solucion = orchestrator.ejecutarFlujoCompleto(datos, config);

System.out.println("Costo: " + solucion.getCostoTotal());
System.out.println("No asignados: " + solucion.getPaquetesNoAsignados().size());
```

### Ejemplo 2: solo Fase 1

```java
PlanificadorRutasStrategy planificador = new ALNS_RutasPlanner(semilla);
Map<String, Ruta> rutas = planificador.planificarRutas(datos, config);
```

### Ejemplo 3: Fase 1 y Fase 2 por separado

```java
PlanificadorRutasStrategy planificador = new ALNS_RutasPlanner(semilla);
Map<String, Ruta> rutasSeleccionadas = planificador.planificarRutas(datos, config);

MinCostFlowAssigner asignador = new MinCostFlowAssigner();
Map<String, Ruta> rutasValidadas = asignador.asignarEnviosAVuelos(rutasSeleccionadas, datos, config);
```

---

## Función de costo global

La evaluación global se calcula con la utilidad compartida del proyecto:

```
costo = (noAsignados × 10000) + (fueraDePlazo × 2500) + (colapso × 5000) + horasAcumuladas
```

La Fase 2 no vuelve a puntuar rutas alternativas. Solo valida la selección de la Fase 1 y actualiza el estado operacional.

---

## Ventajas de la Arquitectura

| Aspecto | Ventaja |
|--------|---------|
| **Separación** | Cada fase tiene responsabilidad única y clara |
| **Flexibilidad** | Cambiar metaheurísticos o estrategia de asignación sin afectar la otra fase |
| **Reutilización** | Ambas fases pueden usarse independientemente |
| **Escalabilidad** | Cada fase puede optimizarse sin conocer detalles de la otra |
| **Testabilidad** | Fácil de probar cada componente en aislamiento |
| **Mantenibilidad** | Código más modular y fácil de entender |

---

## Flujo de datos visual

```
Dataset + Config
    ↓
    ├─ FASE 1: planificación de rutas
    │  (ALNS)
    │  ↓
    │  Map<String, Ruta>
    │  (ruta seleccionada por paquete)
    │
    └─ FASE 2: validación determinística
       ↓
       Map<String, Ruta>
       (ruta aceptada)
       ↓
       Evaluación global
       ↓
       Solucion completa
```

---

## Comparación: antes vs después

### Antes
```
Dataset → Metaheurístico + evaluación local → Solucion
```
- Difícil cambiar la validación operacional.
- Difícil comparar estrategias sin mezclar criterios.
- Difícil reutilizar el resultado de Fase 1.

### Después
```
Dataset → Fase 1: rutas → Fase 2: validación → Solucion
```
- La construcción y la validación quedan separadas.
- La evaluación global se comparte entre algoritmos.
- El pipeline puede ejecutar ALNS con la misma entrada.

---

## Cómo se usa ahora

### Estado actual: `StandardExperimentPipeline`

El pipeline estándar automatiza el flujo completo:

```
Dataset
  ↓
Determinar fechas de envío (rango explícito, fecha fija, max, o índice)
  ↓
Calcular ventana de vuelos centrada en fechas de envío
  ↓
Cargar paquetes y vuelos filtrados
  ↓
Ejecutar algoritmo seleccionado (ALNS)
  ↓
Validar rutas seleccionadas (Fase 2)
  ↓
Evaluar solución y exportar log JSON
```

### Compilación y ejecución

```bash
javac -encoding UTF-8 -d out $(find src -name "*.java")
java -cp out tasf.app.Main
java -cp out tasf.app.Main --algoritmo=ALNS --fecha-envios=max
```

Ver [README.md](README.md) y [data/README.md](data/README.md) para detalles completos.

---

## Documentación de soporte

- [README.md](README.md)
- [ARQUITECTURA_DOS_FASES.md](ARQUITECTURA_DOS_FASES.md)
- [data/README.md](data/README.md)

---

## Validación

- Arquitectura de dos fases funcional.
- Pipeline integrado en `Main`.
- Evaluación global compartida en ALNS.
- Log JSON generado en `data/output/` con sección `fases` detallada.
- Log TXT de diagnóstico detallado en `data/output/log_detalle_*.txt`.

---

## Formato de logs

### Log JSON (`data/output/log_YYYYMMDD_HHMMSS.json`)

```json
{
  "metadata": {
    "algoritmo": "ALNS",
    "tipoSeleccionFecha": "rango|fecha_fija|dia_maximo|rango_indice|dia_indice",
    "fechaSeleccionada": "2026-01-01 a 2026-01-07",
    "maletasTotales": 103,
    "maletasAsignadas": 103,
    "pedidosTotales": 52,
    "pedidosAsignados": 52,
    "rangoDiasVuelos": "2025-12-30 a 2026-01-04",
    "hayColapso": false,
    "maletasFueraDePlazo": 0,
    "pedidosSinAsignar": 0,
    "costoTotal": 603.33,
    "duracionMs": 828,
    "generado": "2026-06-17T13:11:34"
  },
  "fases": {
    "fase1_planificacion": {
      "descripcion": "ALNS metaheuristico: genera ruta optima por paquete usando busqueda adaptativa con destroy/repair",
      "tiempoMs": 815,
      "totalPaquetes": 52,
      "paquetesConRuta": 52,
      "paquetesSinRuta": 0
    },
    "fase2_validacion": {
      "descripcion": "MinCostFlow determinista: valida capacidad, ocupacion y ventanas temporales. Busca alternativas si la ruta no es factible.",
      "tiempoMs": 4,
      "rutasRecibidas": 52,
      "rutasAceptadas": 52,
      "rutasRechazadas": 0
    },
    "fase3_evaluacion": {
      "descripcion": "Calculo de costo final: noAsignados*10000 + fueraPlazo*2500 + colapso*5000 + horasAcumuladas",
      "tiempoMs": 2
    }
  },
  "configuracion": {
    "modo": "default",
    "iteracionesALNS": 20,
    "maxRutasPorPaquete": 4,
    "maxEscalas": 2,
    "horizonteBusquedaHoras": 72,
    "evaporacionFeromona": 0.40,
    "porcentajeRuptura": 0.15
  },
  "diagnosticoFueraDePlazo": [...],
  "asignaciones": [...]
}
```

### Log TXT (`data/output/log_detalle_YYYYMMDD_HHMMSS.txt`)

```
[CONFIG ADAPTATIVA] modo=default | paquetes=52 | iteraciones=20, maxRutas=4, ruptura=15%, evaporacion=0.40
[BUSQUEDA RUTAS] 52 tareas de busqueda paralela completadas [653ms]
=== FASES DEL PIPELINE ===

FASE 1 - Planificacion de Rutas (ALNS metaheuristico)
  Descripcion:  Genera ruta optima por paquete usando busqueda adaptativa con destroy/repair
  Tiempo:       815 ms
  Resultados:   52/52 paquetes obtuvieron ruta

FASE 2 - Validacion y Asignacion (MinCostFlow determinista)
  Descripcion:  Valida capacidad, ocupacion y ventanas temporales. Busca alternativas si la ruta no es factible.
  Tiempo:       4 ms
  Resultados:   52/52 rutas aceptadas

FASE 3 - Evaluacion Final
  Descripcion:  Calculo de costo: noAsignados*10000 + fueraPlazo*2500 + colapso*5000 + horasAcumuladas
  Tiempo:       2 ms
==========================
```

---

## Próximos pasos

1. Ajustar operadores de refinamiento.
2. Afinar la política de aceptación de ALNS.
3. Añadir métricas extra de colapso.
4. Explorar paralelización de corridas.
