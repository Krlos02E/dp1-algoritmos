# DP1-Algoritmos

Sistema de planificación logística con arquitectura de dos fases.

1. Fase 1: metaheurísticas ACO y ALNS seleccionan una ruta por paquete.
2. Fase 2: un asignador determinístico valida y reserva la ruta completa paquete -> ruta -> vuelos.

El flujo está orientado a experimentación automatizada, con métricas de costo, tardanza, capacidad, colapso y porcentaje de éxito.

---

## Contenido

- [Quick Start](#quick-start)
- [Descripción general](#descripción-general)
- [Dos fases](#dos-fases)
- [Función objetivo global](#función-objetivo-global)
- [Flujo de ejecución](#flujo-de-ejecución)
- [Cómo ejecutar](#cómo-ejecutar)
- [Estructura principal del proyecto](#estructura-principal-del-proyecto)
- [Notas de diseño](#notas-de-diseño)
- [Documentación relacionada](#documentación-relacionada)

---

## 🚀 Quick Start

### 1. Compilar

```bash
cd /home/rvs/PUCP/DP1/dp1-algoritmos
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
3. Si `--dias-vuelos=0`, carga todos los vuelos disponibles.
4. Calcula estadísticas diarias de envíos y capacidad.
5. Si no se usa `--fecha-envios`, genera niveles de carga y selecciona el día histórico más cercano para cada nivel.
6. **Ejecuta AMBOS algoritmos (ALNS y ACO)** en paralelo:
   - Por defecto: 10 corridas de ALNS + 10 corridas de ACO
   - Personalizable con `--corridas=N`
7. Para cada algoritmo: planifica rutas, asigna envíos y evalúa si llegan dentro del plazo.
8. Escribe `experimentos_raw_*.csv` y `experimentos_resumen_*.csv` en `data/output/`.

### 3. Ejecutar (modo personalizado)

```bash
# Cambiar directorio de datos
java -cp out tasf.app.Main --data-dir=/ruta/a/datos

# Cambiar ventana de vuelos
java -cp out tasf.app.Main \
  --fecha-inicio-vuelos=2026-01-05 \
  --dias-vuelos=7

# Evaluar un día completo de envíos
java -cp out tasf.app.Main \
  --corridas=1 \
  --max-envios=0 \
  --fecha-envios=max

# Evaluar una fecha concreta de envíos
java -cp out tasf.app.Main \
  --corridas=1 \
  --max-envios=0 \
  --fecha-envios=2026-11-02

# Barrer porcentajes de paquetes del día seleccionado hasta encontrar el primer subconjunto que quede todo dentro del plazo
java -cp out tasf.app.Main \
  --corridas=1 \
  --max-envios=0 \
  --fecha-envios=max \
  --barrer-porcentaje-envios \
  --porcentaje-envios-inicial=100 \
  --porcentaje-envios-minimo=10 \
  --paso-porcentaje-envios=5

# Cambiar semillas de aleatoriedad
java -cp out tasf.app.Main \
  --semilla-alns=42 \
  --semilla-aco=99
```

### 4. Parámetros CLI

| Parámetro | Valor Default | Descripción |
|-----------|---------------|-------------|
| `--data-dir` | `data` | Directorio raíz de datos |
| `--fecha-inicio-vuelos` | `2026-01-02` | Fecha de inicio de la ventana de vuelos |
| `--dias-vuelos` | `0` | Cantidad de días a incluir; `0` significa cargar todos los vuelos disponibles |
| `--max-envios` | `0` (todos) | Límite de envíos por archivo (0 = sin límite) |
| `--corridas` | `10` | Corridas por algoritmo |
| `--fecha-envios` | - | Día de envíos a evaluar; `max` usa el día con más envíos |
| `--barrer-porcentaje-envios` | desactivado | Activa un barrido descendente de porcentaje del día seleccionado |
| `--porcentaje-envios-inicial` | `100` | Porcentaje inicial del barrido |
| `--porcentaje-envios-minimo` | `10` | Porcentaje mínimo del barrido |
| `--paso-porcentaje-envios` | `5` | Paso entre porcentajes |
| `--semilla-alns` | `17` | Semilla aleatoria para ALNS |
| `--semilla-aco` | `17` | Semilla aleatoria para ACO |

**⚠️ Nota Importante**: No hay parámetro para ejecutar solo un algoritmo. **Siempre se ejecutan AMBOS** (ACO y ALNS) en cada configuración. Puedes cambiar sus semillas con `--semilla-alns` y `--semilla-aco`, pero ambos corren independientemente.

### 5. Troubleshooting

### Error: "No se encontraron archivos"
Verifica que existan `data/input/aeropuertos/`, `data/input/vuelos/planes_vuelo.txt` y `data/input/envios/`.

### Error: "No hay datos de entrada"
Revisa formatos y codificación de archivos.

### Pocos paquetes asignados
Es normal si los niveles de carga son muy altos, el día elegido tiene muchos envíos tardíos o la ventana de vuelos es pequeña.
Usa `--dias-vuelos=0` para cargar todos los vuelos.
Usa `--fecha-envios=max` para evaluar el día más cargado.
Usa `--barrer-porcentaje-envios` si quieres reducir progresivamente la muestra hasta que cumpla plazo.

### Error al compilar
Asegúrate de usar Java 8+ con `java -version`.
---

## Algoritmos utilizados

El sistema ejecuta ambos algoritmos en cada corrida: ALNS y ACO.

### ALNS
- Construye una solución completa paquete -> ruta.
- Usa destrucción multi-paquete, reparación con orden variable y aceptación tipo simulated annealing.
- Mantiene la mejor solución global encontrada.

### ACO
- Construye soluciones por hormigas.
- Evalúa el costo global durante la construcción.
- Aplica una mejora local posterior a la construcción.

### Cómo se ejecutan
- Para cada configuración, ALNS y ACO corren por separado.
- `--corridas=N` significa N corridas de ALNS y N corridas de ACO.
- Los resultados se guardan en CSV raw y CSV resumen.

---

## Arquitectura

La solución sigue dividida en dos fases:

1. Fase 1: `ACO_RutasPlanner` o `ALNS_RutasPlanner` producen una ruta seleccionada por paquete.
2. Fase 2: `MinCostFlowAssigner` valida y reserva la ruta seleccionada sobre el estado operacional.

El flujo completo lo ejecuta `StandardExperimentPipeline` desde `Main`.

---

## Ejecución

El sistema está diseñado para experimentos automatizados, no simulaciones interactivas.

1. Carga aeropuertos, vuelos y envíos desde `data/input/`.
2. Calcula capacidad máxima diaria y distribución de envíos.
3. Genera niveles de carga o selecciona un día concreto.
4. Ejecuta ALNS y ACO con las semillas indicadas.
5. Cada algoritmo construye una solución paquete -> ruta.
6. La Fase 2 valida la factibilidad y reserva la ruta completa.
7. Se evalúa la solución final y se exportan CSV en `data/output/`.

---

## Framework de experimentación

`StandardExperimentPipeline` automatiza el flujo completo:

```java
StandardExperimentPipeline pipeline = new StandardExperimentPipeline(
    dataDir,
    fechaInicio,
    diasVuelos,
    maxEnviosPorArchivo,
    corridasPorAlgoritmo,
    fechaEnviosFiltro,
    usarDiaMaximoEnvios,
    barrerPorcentajeEnvios,
    porcentajeEnviosInicial,
    porcentajeEnviosMinimo,
    pasoPorcentajeEnvios,
    algoritmos
);
```

La ejecución estándar desde `Main` registra dos `AlgorithmSpec`:

- `ALNS` -> `ALNS_RutasPlanner`
- `ACO` -> `ACO_RutasPlanner`

---

## Métricas y resultados

Cada corrida guarda:

- algoritmo
- nivel objetivo o fecha seleccionada
- paquetes asignados / no asignados
- porcentaje de éxito
- maletas fuera de plazo
- colapso detectado
- costo total
- tiempo de ejecución

Los CSV resultantes quedan en `data/output/`.

---

## Documentación relacionada

- [data/README.md](data/README.md)
- [ARQUITECTURA_DOS_FASES.md](ARQUITECTURA_DOS_FASES.md)
- [IMPLEMENTACION_DOS_FASES.md](IMPLEMENTACION_DOS_FASES.md)
- [GUIA_DISTRIBUCION_ENVIOS.md](GUIA_DISTRIBUCION_ENVIOS.md)
- [GUIA_GENERADOR_NIVELES_CARGA.md](GUIA_GENERADOR_NIVELES_CARGA.md)

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

La función objetivo global vive en `PlanificacionUtils.evaluarAsignacion(...)` y no en `MinCostFlowAssigner`.

---

## Contacto y soporte

- Arquitectura: `ARQUITECTURA_DOS_FASES.md`
- Utilidades: `GUIA_*.md`
- Datos: `data/README.md`

**Nota**: La carpeta `src/tasf/examples/` ya no forma parte del proyecto.
