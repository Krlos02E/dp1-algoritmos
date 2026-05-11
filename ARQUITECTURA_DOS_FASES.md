# Arquitectura de Dos Fases: Planificación de Rutas + Asignación Determinística de Envíos

## Descripción general

La solución se ejecuta en dos fases coordinadas:

### Fase 1: planificación de rutas con metaheurísticas
- Responsabilidad: construir una solución completa paquete → ruta.
- Algoritmos: ACO y ALNS (se ejecuta uno por invocación).
- Salida: `Map<String, Ruta>` con una ruta seleccionada por paquete.
- Ubicación: `tasf.strategy.aco.ACO_RutasPlanner` y `tasf.strategy.alns.ALNS_RutasPlanner`.

### Fase 2: asignación determinística
- Responsabilidad: validar y reservar la ruta ya seleccionada.
- Algoritmo: validación determinística con `EstadoOperacional` y `MinCostFlowAssigner`.
- Entrada: `Map<String, Ruta>` producido en la Fase 1.
- Salida: `Map<String, Ruta>` aceptada por factibilidad operacional.
- Ubicación: `tasf.strategy.flow.MinCostFlowAssigner`.

ACO y ALNS no entregan listas de candidatos al orquestador externo. Construyen soluciones completas, las puntúan con costo global y luego la Fase 2 valida capacidad, ocupación y restricciones temporales sobre la ruta elegida.

---

## Estructura de clases

### Interfaces

#### `PlanificadorRutasStrategy`
```java
public interface PlanificadorRutasStrategy {
    Map<String, Ruta> planificarRutas(Dataset datos, Config_Simulacion config);
}
```
Contrato de Fase 1: devuelve una ruta seleccionada por paquete.

#### `PlanificadorStrategy`
Interfaz interna que usan `ALNS_Strategy` y `ACO_Strategy` para la lógica metaheurística pura. Devuelve una `Solucion` completa.

#### `Asignador`
```java
public interface Asignador {
    Map<String, Ruta> asignar(Map<String, Ruta> rutasPlanificadas, Dataset datos, Config_Simulacion config);
}
```
Contrato de Fase 2: valida la ruta ya elegida y la reserva si sigue siendo factible.

### Implementaciones de la Fase 1

#### `ACO_RutasPlanner`
- Implementa `PlanificadorRutasStrategy`.
- Ejecuta `ACO_Strategy` y expone su solución paquete → ruta.

#### `ALNS_RutasPlanner`
- Implementa `PlanificadorRutasStrategy`.
- Ejecuta `ALNS_Strategy` y expone su solución paquete → ruta.

### Implementaciones de la Fase 2

#### `MinCostFlowAsignador`
- Implementa la interfaz `Asignador`.
- Delega en `MinCostFlowAssigner` para la lógica de validación.

#### `MinCostFlowAssigner`
- Recorre los paquetes en orden temporal.
- Usa `EstadoOperacional` para verificar capacidad, ocupación y ventana temporal.
- Si la ruta seleccionada no es factible, busca rutas alternativas entre candidatas precomputadas.

### Orquestador de dos fases

#### `TwoPhaseOrchestrator`
```java
public class TwoPhaseOrchestrator {
    public Solucion ejecutarFlujoCompleto(Dataset datos, Config_Simulacion config)
}
```

Flujo:

1. Fase 1: el planificador devuelve `Map<String, Ruta>`.
2. Fase 2: el asignador valida y reserva esa selección.
3. Evaluación: `PlanificacionUtils.evaluarAsignacion(...)` calcula la solución final.

---

## Uso Ejemplos

### Opción 1: Flujo Completo de Dos Fases

```java
// Crear el orquestador con ACO
PlanificadorRutasStrategy planificador = new ACO_RutasPlanner(semilla);
TwoPhaseOrchestrator orchestrator = new TwoPhaseOrchestrator(planificador);

// Ejecutar ambas fases
Solucion solucion = orchestrator.ejecutarFlujoCompleto(datos, config);
```

### Opción 2: solo planificación de rutas

```java
PlanificadorRutasStrategy planificador = new ALNS_RutasPlanner(semilla);
Map<String, Ruta> rutas = planificador.planificarRutas(datos, config);
```

### Opción 3: validar rutas externas

```java
Map<String, Ruta> rutasExternas = obtenerDeFuente();
MinCostFlowAssigner asignador = new MinCostFlowAssigner();
Map<String, Ruta> rutasValidadas = asignador.asignarEnviosAVuelos(rutasExternas, datos, config);
```

---

## Flujos de datos

### Fase 1: planificación de rutas

```
Dataset (paquetes, vuelos, aeropuertos)
    ↓
PlanificadorRutasStrategy.planificarRutas()
    ↓
Map<String, Ruta>
    (ej: PKG-001 → Ruta A)
```

### Fase 2: validación determinística

```
Map<String, Ruta>
    ↓
MinCostFlowAssigner / Asignador
    ↓
Map<String, Ruta> validada
    (ej: PKG-001 → Ruta A aceptada)
```

### Orquestador: flujo completo

```
Dataset + Config
    ↓
Fase 1: PlanificadorRutas → Map<String, Ruta>
    ↓
Fase 2: Validación → Map<String, Ruta>
    ↓
Evaluación final → Solucion
```

---

## Función objetivo

La evaluación global vive en `PlanificacionUtils.evaluarAsignacion(...)` y combina:

```
costo = (noAsignados × 10000) + (fueraDePlazo × 2500) + (colapso × 5000) + horasAcumuladas
```

La Fase 2 no reoptimiza la asignación; solo verifica si la ruta elegida sigue siendo factible.

---

## Ventajas de la arquitectura de dos fases

1. Separa la construcción de soluciones de la validación operacional.
2. Permite cambiar ACO o ALNS sin tocar la lógica de validación.
3. Centraliza la evaluación global en una sola función compartida.
4. Facilita experimentación y comparación de algoritmos.
5. Mantiene el flujo determinista en la segunda fase.

---

## Parámetros de configuración

La configuración activa del pipeline se concentra en `Main` y `StandardExperimentPipeline`:

| Parámetro | Default | Descripción |
|-----------|---------|-------------|
| `--data-dir` | `data` | Directorio raíz de datos |
| `--algoritmo` | `ALNS` | `ALNS` o `ACO` |
| `--fecha-inicio-vuelos` | `2026-01-01` | Fecha de inicio de la ventana de vuelos |
| `--dias-vuelos` | `3` | Días de vuelos; `0` = todos (~1095 días) |
| `--max-envios` | `0` | Límite de envíos por archivo |
| `--fecha-envios` | `max` | Índice (`5`), fecha (`2026-01-06`), o `max` |
| `--duracion-envios` | `1` | Días consecutivos de envíos |
| `--rango-envios` | - | `2026-01-01:2026-01-07` o índice `3-7` |
| `--semilla-alns` | `17` | Semilla para ALNS |
| `--semilla-aco` | `17` | Semilla para ACO |

---

## Integración con `StandardExperimentPipeline`

El pipeline estándar:

1. Carga datos automáticamente.
2. Determina qué fecha(s) de envíos procesar (rango explícito, fecha fija, día máximo, o índice).
3. Calcula la ventana efectiva de vuelos centrada en las fechas de envío.
4. Ejecuta el algoritmo seleccionado sobre la misma entrada.
5. Evalúa la solución con la función global compartida.
6. Exporta un log JSON en `data/output/`.

### Selección de fechas de envío

El pipeline soporta múltiples modos:

- **Rango explícito** (`--rango-envios=2026-01-01:2026-01-07`): procesa todos los paquetes del rango completo.
- **Índice numérico** (`--fecha-envios=5`): día relativo a `fechaInicioVuelos`.
- **Fecha específica** (`--fecha-envios=2026-01-06`): fecha exacta.
- **Día máximo** (`--fecha-envios=max`): escanea todos los archivos y selecciona el día con más envíos (default).

### Contenido del log JSON

Cada log generado en `data/output/log_YYYYMMDD_HHMMSS.json` contiene:

- **metadata**: algoritmo, tipo de selección de fecha, fecha seleccionada, métricas de la corrida
- **escaneo**: días escaneados, tiempo de escaneo, total de envíos (solo en modo `dia_maximo`)
- **configuracion**: modo adaptativo, iteraciones, hormigas, evaporación, etc.
- **diagnosticoFueraDePlazo**: detalle de paquetes fuera de plazo con ruta completa (solo si hay)
- **asignaciones**: lista de paquetes asignados con vuelos y tiempos

---

## Próximas mejoras sugeridas

1. Mejorar los operadores de reparación de ALNS.
2. Ajustar la política local de refinamiento en ACO.
3. Añadir métricas más detalladas de colapso.
4. Explorar paralelización en la evaluación de corridas.
