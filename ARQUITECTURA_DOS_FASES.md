# Arquitectura de Dos Fases: Planificación de Rutas + Asignación Determinística de Envíos

## Descripción general

La solución se ejecuta en dos fases coordinadas:

### Fase 1: planificación de rutas con metaheurísticas
- Responsabilidad: construir una solución completa paquete -> ruta.
- Algoritmos: ACO y ALNS.
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
Interfaz histórica que sigue existiendo para las estrategias completas ACO/ALNS.

#### `Asignador`
```java
public interface Asignador {
    Map<String, Ruta> asignar(Map<String, Ruta> rutasPlanificadas, Dataset datos, Config_Simulacion config);
}
```
Contrato de Fase 2: valida la ruta ya elegida y la reserva si sigue siendo factible.

### Implementaciones de la Fase 1

#### `ACO_RutasPlanner`
- Enlaza `PlanificadorStrategy` con `PlanificadorRutasStrategy`.
- Ejecuta `ACO_Strategy` y expone su solución paquete -> ruta.

#### `ALNS_RutasPlanner`
- Enlaza `PlanificadorStrategy` con `PlanificadorRutasStrategy`.
- Ejecuta `ALNS_Strategy` y expone su solución paquete -> ruta.

### Implementaciones de la Fase 2

#### `MinCostFlowAsignador`
- Adapta la interfaz `Asignador`.
- Aplica validación determinística sobre rutas seleccionadas.

#### `MinCostFlowAssigner`
- Recorre los paquetes en orden temporal.
- Usa `EstadoOperacional` para verificar capacidad, ocupación y ventana temporal.
- Acepta o rechaza la ruta completa sin volver a escoger rutas alternativas.

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

- horas totales de transporte
- penalización por paquetes no asignados
- penalización por entrega fuera de plazo
- penalización por colapso operacional

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

- `--data-dir`
- `--fecha-inicio-vuelos`
- `--dias-vuelos`
- `--max-envios`
- `--corridas`
- `--fecha-envios`
- `--barrer-porcentaje-envios`
- `--porcentaje-envios-inicial`
- `--porcentaje-envios-minimo`
- `--paso-porcentaje-envios`
- `--semilla-alns`
- `--semilla-aco`

---

## Integración con `StandardExperimentPipeline`

El pipeline estándar:

1. Carga datos automáticamente.
2. Calcula capacidad diaria y distribuciones.
3. Genera niveles de carga o usa una fecha fija.
4. Ejecuta ALNS y ACO sobre la misma entrada.
5. Evalúa cada solución con la función global compartida.
6. Exporta resultados raw y resumen a CSV.

---

## Próximas mejoras sugeridas

1. Mejorar los operadores de reparación de ALNS.
2. Ajustar la política local de refinamiento en ACO.
3. Añadir métricas más detalladas de colapso.
4. Explorar paralelización en la evaluación de corridas.

