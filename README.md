# DP1-Algoritmos

Sistema de planificación logística con arquitectura de dos fases.

1. Fase 1: metaheurística (ACO o ALNS) selecciona una ruta por paquete.
2. Fase 2: un asignador determinístico valida y reserva la ruta completa paquete → ruta → vuelos.

El flujo está orientado a experimentación automatizada, con métricas de costo, tardanza, capacidad, colapso y porcentaje de éxito.

---

## Contenido

- [Quick Start](#quick-start)
- [Descripción general](#descripción-general)
- [Dos fases](#dos-fases)
- [Función objetivo global](#función-objetivo-global)
- [Cómo ejecutar](#cómo-ejecutar)
- [Parámetros CLI](#parámetros-cli)
- [Estructura principal del proyecto](#estructura-principal-del-proyecto)
- [Documentación relacionada](#documentación-relacionada)

---

## Quick Start

### 1. Compilar

```bash
mkdir -p out
javac -encoding UTF-8 -d out $(find src -name "*.java")
```

### 2. Ejecutar (uso estándar)

```bash
java -cp out tasf.app.Main
```

Esto hace, en este orden:

1. Lee la CLI y arma el `StandardExperimentPipeline`.
2. Carga aeropuertos, vuelos y paquetes desde `data/input/`.
3. Calcula estadísticas diarias de envíos y capacidad.
4. Ejecuta el algoritmo seleccionado (ALNS por defecto).
5. Planifica rutas, asigna envíos y evalúa si llegan dentro del plazo.
6. Escribe un log JSON en `data/output/`.

### 3. Ejecutar (modo personalizado)

```bash
# Cambiar algoritmo
java -cp out tasf.app.Main --algoritmo=ACO

# Ventana de vuelos personalizada
java -cp out tasf.app.Main \
  --fecha-inicio-vuelos=2026-01-05 \
  --dias-vuelos=7

# Evaluar un día concreto de envíos (por índice)
java -cp out tasf.app.Main --fecha-envios=5

# Evaluar una fecha concreta de envíos
java -cp out tasf.app.Main --fecha-envios=2026-01-06

# Usar el día con más envíos
java -cp out tasf.app.Main --fecha-envios=max

# Rango de fechas explícito (nuevo)
java -cp out tasf.app.Main --rango-envios=2026-01-01:2026-01-07

# Rango por índice numérico
java -cp out tasf.app.Main --rango-envios=3-7

# Cargar todos los vuelos (~1095 días)
java -cp out tasf.app.Main --dias-vuelos=0 --fecha-envios=max

# Semillas personalizadas
java -cp out tasf.app.Main \
  --semilla-alns=42 \
  --semilla-aco=99
```

### 4. Parámetros CLI

| Parámetro | Valor Default | Descripción |
|-----------|---------------|-------------|
| `--data-dir` | `data` | Directorio raíz de datos |
| `--algoritmo` | `ALNS` | Algoritmo a ejecutar: `ALNS` o `ACO` |
| `--fecha-inicio-vuelos` | `2026-01-02` | Fecha de inicio de la ventana de vuelos |
| `--dias-vuelos` | `3` | Cantidad de días de vuelos; `0` carga todos (~1095 días) |
| `--max-envios` | `0` (todos) | Límite de envíos por archivo (0 = sin límite) |
| `--fecha-envios` | `max` | Día de envíos: índice numérico (`5`), fecha (`2026-01-06`), o `max` |
| `--duracion-envios` | `1` | Número de días consecutivos de envíos |
| `--rango-envios` | - | Rango de fechas: `2026-01-01:2026-01-07` o índice `3-7` |
| `--semilla-alns` | `17` | Semilla aleatoria para ALNS |
| `--semilla-aco` | `17` | Semilla aleatoria para ACO |

**Nota sobre `--dias-vuelos`**: cuando se usa `--fecha-envios` o `--rango-envios` sin especificar `--dias-vuelos`, se calcula automáticamente a 3 días (basado en plazos de 24h/48h + 24h buffer).

### 5. Troubleshooting

**Error: "No se encontraron archivos"**
Verifica que existan `data/input/aeropuertos/`, `data/input/vuelos/planes_vuelo.txt` y `data/input/envios/`.

**Error: "No hay datos de entrada"**
Revisa formatos y codificación de archivos. El archivo de aeropuertos usa UTF-16 LE; el resto UTF-8.

**Pocos paquetes asignados**
Es normal si la ventana de vuelos es pequeña. Usa `--dias-vuelos=0` para cargar todos los vuelos.

**Error al compilar**
Asegúrate de usar Java 8+ con `java -version`.

---

## Algoritmos

El sistema ejecuta **un algoritmo por invocación**, seleccionado con `--algoritmo=`.

### ALNS (Adaptive Large Neighborhood Search)
- Construye una solución greedy inicial.
- Iterativamente destruye y repara subconjuntos de rutas.
- Usa simulated annealing para aceptar soluciones peores.
- Terminación temprana tras 3 iteraciones sin mejora.
- Operadores de ruptura: random, worst-delay, congestión.
- Operadores de reparación: greedy, regret.

### ACO (Ant Colony Optimization)
- Múltiples hormigas construyen soluciones en paralelo.
- Feromonas guían la selección probabilística de rutas.
- Depósito de feromonas por ranking (elite) y global-best.
- Reinicio controlado tras estancamiento prolongado.
- Perturbación Lévy para escapar óptimos locales.
- Refinamiento local post-construcción.

### Pipeline compartido
Ambos algoritmos pasan por el mismo orquestador `TwoPhaseOrchestrator`:
1. Fase 1: el algoritmo produce `Map<String, Ruta>`.
2. Fase 2: `MinCostFlowAsignador` valida factibilidad operacional.
3. Evaluación: `PlanificacionUtils.evaluarAsignacion()` calcula el costo global.

---

## Arquitectura

La solución sigue dividida en dos fases:

1. **Fase 1**: `ACO_RutasPlanner` o `ALNS_RutasPlanner` producen una ruta seleccionada por paquete.
2. **Fase 2**: `MinCostFlowAsignador` valida y reserva la ruta seleccionada sobre el estado operacional.

El flujo completo lo ejecuta `StandardExperimentPipeline` desde `Main`.

---

## Ejecución

El sistema está diseñado para experimentos automatizados, no simulaciones interactivas.

1. Carga aeropuertos, vuelos y envíos desde `data/input/`.
2. Determina qué fecha(s) de envíos procesar (rango explícito, fecha fija, día máximo, o índice).
3. Calcula la ventana efectiva de vuelos centrada en las fechas de envío.
4. Ejecuta el algoritmo seleccionado con las semillas indicadas.
5. El algoritmo construye una solución paquete → ruta.
6. La Fase 2 valida la factibilidad y reserva la ruta completa.
7. Se evalúa la solución final y se exporta un log JSON en `data/output/`.

---

## Métricas y resultados

Cada corrida registra:

- algoritmo
- fecha seleccionada
- paquetes asignados / no asignados
- porcentaje de éxito
- maletas fuera de plazo
- colapso detectado
- costo total
- tiempo de ejecución

Los logs JSON quedan en `data/output/` con nombre `log_YYYYMMDD_HHMMSS.json`.

---

## Documentación relacionada

- [data/README.md](data/README.md)
- [ARQUITECTURA_DOS_FASES.md](ARQUITECTURA_DOS_FASES.md)
- [IMPLEMENTACION_DOS_FASES.md](IMPLEMENTACION_DOS_FASES.md)
- [GUIA_DISTRIBUCION_ENVIOS.md](GUIA_DISTRIBUCION_ENVIOS.md)

---

## Validación

```bash
javac -encoding UTF-8 -d out $(find src -name "*.java")
java -cp out tasf.app.Main
java -cp out tasf.tests.PlannerTests
```

---

## Desarrollo

### Agregar un nuevo metaheurístico

1. Implementar `PlanificadorRutasStrategy`.
2. Retornar `Map<String, Ruta>`.
3. Registrar la estrategia en `Main.java` como un nuevo `AlgorithmSpec`.

### Criterio de aceptación

La función objetivo global vive en `PlanificacionUtils.evaluarAsignacion(...)`.

---

## Contacto y soporte

- Arquitectura: `ARQUITECTURA_DOS_FASES.md`
- Utilidades: `GUIA_*.md`
- Datos: `data/README.md`

**Nota**: La carpeta `src/tasf/examples/` ya no forma parte del proyecto.
