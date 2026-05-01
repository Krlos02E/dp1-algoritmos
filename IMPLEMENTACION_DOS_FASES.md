# Solución de dos fases: planificación de rutas + validación determinística

## Resumen de implementación

La solución quedó dividida en dos fases coordinadas:

### Fase 1: planificación de rutas
- Algoritmos: ACO y ALNS.
- Salida: `Map<String, Ruta>` con una ruta seleccionada por paquete.
- Contrato: `PlanificadorRutasStrategy`.
- Implementadores: `ACO_RutasPlanner`, `ALNS_RutasPlanner`.

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
├── strategy/
│   ├── PlanificadorRutasStrategy.java           (INTERFAZ NUEVA)
│   ├── TwoPhaseOrchestrator.java               (ORQUESTADOR NUEVO)
│   ├── aco/
│   │   └── ACO_RutasPlanner.java              (IMPLEMENTADOR ACO)
│   ├── alns/
│   │   └── ALNS_RutasPlanner.java             (IMPLEMENTADOR ALNS)
│   ├── flow/
│   │   └── MinCostFlowAssigner.java           (ASIGNADOR DE VUELOS)
│
└── Documentación/
   ├── ARQUITECTURA_DOS_FASES.md              (GUÍA COMPLETA)
   └── IMPLEMENTACION_DOS_FASES.md            (ESTE DOCUMENTO)
```

---

## Uso básico

### Ejemplo 1: flujo completo con ACO

```java
PlanificadorRutasStrategy planificador = new ACO_RutasPlanner(semilla);
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
PlanificadorRutasStrategy planificador = new ACO_RutasPlanner(semilla);
Map<String, Ruta> rutasSeleccionadas = planificador.planificarRutas(datos, config);

MinCostFlowAssigner asignador = new MinCostFlowAssigner();
Map<String, Ruta> rutasValidadas = asignador.asignarEnviosAVuelos(rutasSeleccionadas, datos, config);
```

---

## Función de costo global

La evaluación global se calcula con la utilidad compartida del proyecto:

$$
CostoTotal = HorasTransporte + PenalizacionNoAsignacion + PenalizacionPlazo + PenalizacionColapso
$$

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
    │  (ACO o ALNS)
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
- El pipeline puede ejecutar ALNS y ACO con la misma entrada.

---

## Cómo se usa ahora

### Estado actual: `StandardExperimentPipeline`

El pipeline estándar automatiza el flujo completo:

```
Dataset
  ↓
Capacidad diaria
  ↓
Generación o selección de nivel de carga
  ↓
Para cada configuración:
  ├─ Ejecutar ALNS
  ├─ Ejecutar ACO
  ├─ Validar rutas seleccionadas
  ├─ Detectar colapsos
  └─ Guardar resultados
  ↓
Exportar CSV raw y resumen
```

### Compilación y ejecución

```bash
javac -encoding UTF-8 -d out $(find src -name "*.java")
java -cp out tasf.app.Main
java -cp out tasf.app.Main --corridas=20 --fecha-inicio-vuelos=2026-02-01
```

Ver [README.md](README.md) y [data/README.md](data/README.md) para detalles completos.

---

## Documentación de soporte

- [README.md](README.md)
- [ARQUITECTURA_DOS_FASES.md](ARQUITECTURA_DOS_FASES.md)
- [data/README.md](data/README.md)
- [GUIA_DISTRIBUCION_ENVIOS.md](GUIA_DISTRIBUCION_ENVIOS.md)
- [GUIA_GENERADOR_NIVELES_CARGA.md](GUIA_GENERADOR_NIVELES_CARGA.md)

---

## Validación

- Arquitectura de dos fases funcional.
- Pipeline integrado en `Main`.
- Evaluación global compartida entre ACO y ALNS.
- CSV raw y resumen generados en `data/output/`.

---

## Próximos pasos

1. Ajustar operadores de refinamiento.
2. Afinar la política de aceptación de ALNS.
3. Añadir métricas extra de colapso.
4. Explorar paralelización de corridas.
