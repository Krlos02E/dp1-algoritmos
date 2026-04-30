# Arquitectura de Dos Fases: Planificación de Rutas + Asignación Determinística de Envíos

## Descripción General

La solución se ha dividido en **dos fases independientes y bien definidas**:

### Fase 1: Planificación de Rutas (Metaheurísticos)
- **Responsabilidad**: Determinar rutas candidatas para cada paquete
- **Algoritmos**: ACO (Ant Colony Optimization) o ALNS (Adaptive Large Neighborhood Search)
- **Salida**: `Map<String, List<Ruta>>` - para cada paquete, una lista de rutas candidatas ordenadas por preferencia
- **Ubicación**: `tasf.strategy.aco.ACO_RutasPlanner` y `tasf.strategy.alns.ALNS_RutasPlanner`

### Fase 2: Asignación de Envíos a Vuelos (Determinístico)
- **Responsabilidad**: Asignar paquetes a vuelos específicos minimizando costo total
- **Algoritmo**: Min-Cost Flow (implementado con enfoque greedy optimizado)
- **Entrada**: Rutas candidatas de la Fase 1
- **Salida**: `Map<String, Vuelo>` - asignación específica de paquete a vuelo
- **Ubicación**: `tasf.strategy.flow.MinCostFlowAssigner`

---

## Estructura de Clases

### Interfaces

#### `PlanificadorRutasStrategy`
```java
public interface PlanificadorRutasStrategy {
    Map<String, List<Ruta>> planificarRutas(Dataset datos, Config_Simulacion config);
}
```
Nueva interfaz para la Fase 1 que devuelve solo rutas candidatas.

#### `PlanificadorStrategy` (original)
Interfaz histórica que sigue existiendo en el código, pero el flujo actual usa `PlanificadorRutasStrategy`.

---

### Implementaciones de la Fase 1

#### `ACO_RutasPlanner implements PlanificadorRutasStrategy`
- Implementa ACO para planificación de rutas
- Devuelve candidatos de rutas sin evaluación de asignación

#### `ALNS_RutasPlanner implements PlanificadorRutasStrategy`
- Implementa ALNS para planificación de rutas
- Devuelve candidatos de rutas sin evaluación de asignación

---

### Implementación de la Fase 2

#### `MinCostFlowAssigner`
```java
public Map<String, Vuelo> asignarEnviosAVuelos(
    Map<String, List<Ruta>> rutasPlanificadas,
    Dataset datos,
    Config_Simulacion config
)
```

**Algoritmo**: Asignador heurístico tipo min-cost con balanceo de carga
1. Ordena paquetes por fecha de creación
2. Para cada paquete, examina todas las opciones de vuelos en sus rutas candidatas
3. Selecciona el vuelo con menor costo total considerando:
   - Horas de transporte
   - Penalización por incumplimiento de plazo
   - Factor de balanceo de carga (evita sobrecargar vuelos)
4. Respeta capacidades de vuelos

---

### Orquestador de Dos Fases

#### `TwoPhaseOrchestrator`
```java
public class TwoPhaseOrchestrator {
    public Solucion ejecutarFlujoCompleto(Dataset datos, Config_Simulacion config)
}
```

**Flujo**:
1. **Fase 1**: Llama al planificador de rutas → obtiene `Map<String, List<Ruta>>`
2. **Fase 2**: Llama al asignador Min-Cost Flow → obtiene `Map<String, Vuelo>`
3. **Evaluación**: Integra ambas fases en una `Solucion` final evaluada

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

### Opción 2: Solo Planificación de Rutas

```java
// Si solo interesa las rutas sin Min-Cost Flow
PlanificadorRutasStrategy planificador = new ALNS_RutasPlanner(semilla);
Map<String, List<Ruta>> rutas = planificador.planificarRutas(datos, config);
```

### Opción 3: Usar el asignador con rutas externas

```java
// Si tienes rutas de otra fuente
Map<String, List<Ruta>> rutasExternas = obtenerDeFuente();
MinCostFlowAssigner asignador = new MinCostFlowAssigner();
Map<String, Vuelo> asignaciones = asignador.asignarEnviosAVuelos(rutasExternas, datos, config);
```

---

## Flujos de Datos

### Fase 1: Planificación de Rutas

```
Dataset (paquetes, vuelos, aeropuertos)
    ↓
PlanificadorRutasStrategy.planificarRutas()
    ↓
Map<String, List<Ruta>> rutasCandidatas
    (ej: PKG-001 → [Ruta A, Ruta B, Ruta C])
```

### Fase 2: Asignación Min-Cost Flow

```
Map<String, List<Ruta>> rutasCandidatas
    ↓
MinCostFlowAssigner.asignarEnviosAVuelos()
    ↓
Map<String, Vuelo> asignacionesEspecificas
    (ej: PKG-001 → Vuelo 123)
```

### Orquestador: Flujo Completo

```
Dataset + Config
    ↓
Fase 1: PlanificadorRutas → Map<String, List<Ruta>>
    ↓
Fase 2: MinCostFlow → Map<String, Vuelo>
    ↓
Evaluación Final → Solucion
```

---

## Funciones Costo Min-Cost Flow

### Costo de Asignación

```
CostoTotal = HorasTransporte + PenalizacionPlazo + FactorBalanceo
```

Donde:
- **HorasTransporte**: `ruta.getHorasTotalesDesde(creacion)`
- **PenalizacionPlazo**: 2500 si `llegada > plazo`, 0 en otro caso
- **FactorBalanceo**: `(cargaVuelo / capacidadVuelo) * 100`

---

## Ventajas de la Arquitectura de Dos Fases

1. **Separación de Responsabilidades**: 
   - Fase 1: Optimización combinatoria de rutas
   - Fase 2: Asignación determinística y eficiente

2. **Flexibilidad**:
   - Puedes usar cualquier metaheurístico en Fase 1
   - Puedes cambiar la estrategia de Fase 2 sin afectar metaheurísticos

3. **Reutilización**:
   - Ambas fases pueden usarse de forma independiente
   - Fácil integración con sistemas externos

4. **Escalabilidad**:
   - Cada fase puede optimizarse independientemente
   - Paralelización posible en futuras versiones

5. **Mantenibilidad**:
   - Código más modular y testeable
   - Cada fase tiene una responsabilidad clara

---

## Parámetros de Configuración

### Min-Cost Flow

En `MinCostFlowAssigner`:
- `COSTO_NO_ASIGNACION` = 10000.0 (penalización por no asignar)
- `COSTO_FUERA_PLAZO` = 2500.0 (penalización por retraso)
- `FACTOR_BALANCEO_CARGA` = 100.0 (penalización por ocupación)

Estos parámetros pueden modificarse en el código fuente si es necesario.

---

## Integración con StandardExperimentPipeline

**Estado Actual**: Esta arquitectura de dos fases se integra automáticamente en `StandardExperimentPipeline`, que:

1. **Carga datos automáticamente**
2. **Calcula capacidad máxima diaria**
3. **Genera niveles de carga** (20%-70% por defecto)
4. **Selecciona días históricos** cercanos a cada nivel objetivo
5. **Ejecuta múltiples corridas**:
   - Para cada nivel de carga
   - Para cada algoritmo (ACO + ALNS)
   - N veces (configurable, 10 por defecto)
6. **Detecta colapsos** (paquetes sin asignar o tardes)
7. **Exporta resultados** a CSV para análisis estadístico (ANOVA, gráficas)

### Uso Simplificado

```java
// Antes: Código manual de dos fases
PlanificadorRutasStrategy planificador = new ACO_RutasPlanner(17L);
TwoPhaseOrchestrator orchestrator = new TwoPhaseOrchestrator(planificador);
Solucion solucion = orchestrator.ejecutarFlujoCompleto(datos, config);

// Ahora: Automático con pipeline estándar
java -cp out tasf.app.Main --corridas=20 --fecha-inicio-vuelos=2026-02-01
// Genera: data/output/experimentos_raw_*.csv y experimentos_resumen_*.csv
```

Ver **[README.md](README.md)** para detalles de ejecución.

---

## Próximas Mejoras Sugeridas

1. **Optimización de Min-Cost Flow**:
   - Implementar algoritmo de Ciclos Negativos
   - Integrar búsqueda local iterativa

2. **Parámetros Adaptativos**:
   - Hacer configurables los costos de asignación
   - Adaptar factores según estadísticas de ejecución

3. **Paralelización**:
   - Procesar paquetes en paralelo en Fase 2
   - Distribuir evaluación entre múltiples threads

4. **Análisis Avanzado**:
   - Identificar cuellos de botella por ruta
   - Análisis de sensibilidad de parámetros
   - Machine Learning para predicción de desempeño
