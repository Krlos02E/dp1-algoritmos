# Solución de Dos Fases: Planificación de Rutas + Asignación de Envíos

## Resumen de Implementación

Se ha dividido exitosamente la solución en **dos fases independientes y bien definidas**:

### ✅ Fase 1: Planificación de Rutas (Metaheurísticos)
- **Algoritmos**: ACO (Ant Colony Optimization) y ALNS (Adaptive Large Neighborhood Search)
- **Salida**: Rutas candidatas para cada paquete
- **Clase Interfaz**: `PlanificadorRutasStrategy`
- **Implementadores**: `ACO_RutasPlanner`, `ALNS_RutasPlanner`

### ✅ Fase 2: Asignación de Envíos a Vuelos (Determinístico)
- **Algoritmo**: Asignador heurístico tipo min-cost con enfoque greedy
- **Entrada**: Rutas candidatas de Fase 1
- **Salida**: Asignación específica de paquete → vuelo
- **Clase**: `MinCostFlowAssigner`

### ✅ Orquestador
- **Clase**: `TwoPhaseOrchestrator`
- **Función**: Coordina ambas fases y retorna `Solucion` evaluada

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

## Uso Básico

### Ejemplo 1: Flujo Completo con ACO

```java
// Crear planificador de rutas
PlanificadorRutasStrategy planificador = new ACO_RutasPlanner(semilla);

// Crear orquestador
TwoPhaseOrchestrator orchestrator = new TwoPhaseOrchestrator(planificador);

// Ejecutar ambas fases
Solucion solucion = orchestrator.ejecutarFlujoCompleto(datos, config);

System.out.println("Costo: " + solucion.getCostoTotal());
System.out.println("No Asignados: " + solucion.getPaquetesNoAsignados().size());
```

### Ejemplo 2: Solo Fase 1 (Planificación de Rutas)

```java
// Obtener solo rutas candidatas sin asignación de vuelos
PlanificadorRutasStrategy planificador = new ALNS_RutasPlanner(semilla);
Map<String, List<Ruta>> rutas = planificador.planificarRutas(datos, config);
```

### Ejemplo 3: Ambas Fases Separadas

```java
// Fase 1
PlanificadorRutasStrategy planificador = new ACO_RutasPlanner(semilla);
Map<String, List<Ruta>> rutas = planificador.planificarRutas(datos, config);

// Fase 2
MinCostFlowAssigner asignador = new MinCostFlowAssigner();
Map<String, Vuelo> asignaciones = asignador.asignarEnviosAVuelos(rutas, datos, config);
```

---

## Función de Costo del Asignador

```
CostoAsignacion = HorasTransporte + PenalizacionPlazo + FactorBalanceoCarga

Donde:
  • HorasTransporte: duración desde creación del paquete hasta llegada
  • PenalizacionPlazo: 2500 si sobrepasa plazo de entrega, 0 en otro caso
  • FactorBalanceoCarga: (ocupacion_vuelo / capacidad) × 100
```

**Objetivo**: Minimizar costo total respetando capacidades de vuelos

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

## Flujo de Datos Visual

```
Dataset + Config
    ↓
    ├─ FASE 1: Planificación de Rutas
    │  (ACO o ALNS)
    │  ↓
    │  Map<String, List<Ruta>>
    │  (rutas candidatas)
    │
    └─ FASE 2: Asignación Min-Cost Flow
       ↓
       Map<String, Vuelo>
       (asignaciones específicas)
       ↓
       Evaluación Final
       ↓
       Solucion (completa)
```

---

## Comparación: Antes vs Después

### Antes (Acoplado)
```
Dataset → Metaheurístico + Evaluación (todo junto) → Solucion
```
- Difícil cambiar estrategia de asignación
- Difícil reutilizar rutas en otros contextos
- Difícil paralelizar

### Después (Dos Fases)
```
Dataset → Fase 1: Rutas → Fase 2: Asignación → Solucion
```
- Fácil cambiar cualquier fase
- Fácil reutilizar componentes
- Posibilidad de optimizar cada fase independientemente

---

---

## Cómo se Usa Ahora

### Estado Actual: StandardExperimentPipeline

**La arquitectura de dos fases está completamente integrada en `StandardExperimentPipeline`**, que automatiza el flujo completo:

```
Dataset
  ↓
Capacidad Máxima Diaria
  ↓
Generación de Niveles (20%-70%)
  ↓
Para cada nivel:
  ├─ Seleccionar día histórico cercano
  ├─ Ejecutar ALNS N veces (Fase 1 + Fase 2)
  ├─ Ejecutar ACO N veces (Fase 1 + Fase 2)
  ├─ Detectar colapsos
  └─ Guardar resultados
  ↓
Exportar CSV Raw y Resumen
```

### Compilación y Ejecución

```bash
# Compilar
mkdir -p out
javac -encoding UTF-8 -d out $(find src -name "*.java")

# Ejecutar (uso estándar)
java -cp out tasf.app.Main

# Ejecutar (personalizado)
java -cp out tasf.app.Main --corridas=20 --fecha-inicio-vuelos=2026-02-01
```

Ver **[README.md](README.md)** y **[data/README.md](data/README.md)** para detalles completos.

---

## Documentación de Soporte

1. **[README.md](README.md)**
   - Guía general del sistema
   - Quick start y parámetros CLI
   - Estructura de archivos y análisis de resultados

2. **[ARQUITECTURA_DOS_FASES.md](ARQUITECTURA_DOS_FASES.md)**
   - Descripción técnica de ambas fases
   - Interfaces y clases principales
   - Función de costo Min-Cost Flow

3. **[data/README.md](data/README.md)**
   - Estructura de datos de entrada/salida
   - Formato de archivos esperados
   - Troubleshooting

4. **[GUIA_DISTRIBUCION_ENVIOS.md](GUIA_DISTRIBUCION_ENVIOS.md)**
   - Utilidad para agrupar envíos por fecha
   - Encontrar días cercanos a objetivo

5. **[GUIA_GENERADOR_NIVELES_CARGA.md](GUIA_GENERADOR_NIVELES_CARGA.md)**
   - Generar niveles de carga para experimentos
   - Diferentes rangos y distribuciones

6. **[GUIA_GENERADOR_NIVELES_CARGA.md](GUIA_GENERADOR_NIVELES_CARGA.md)**
   - Generación de niveles de carga para experimentos

---

## Validación

✅ Arquitectura de dos fases completamente funcional
✅ StandardExperimentPipeline automatiza experimentos
✅ Todas las utilidades (capacidad, niveles, distribución) integradas
✅ Detección de colapsos implementada
✅ CSV export para análisis estadístico
✅ 32 archivos Java compilados sin errores
✅ Documentación actualizada

---

## Próximos Pasos (Opcional)

Para usuarios avanzados que deseen customizar:

1. Modificar función de costo en `MinCostFlowAssigner`
2. Crear nuevo metaheurístico implementando `PlanificadorRutasStrategy`
3. Agregar nuevas métricas de colapso en `ColapsoDetector`
4. Implementar algoritmo de Ciclos Negativos en Min-Cost Flow
