# DP1-Algoritmos: Sistema de Planificación Logística de Dos Fases

Sistema de optimización logística que divide la solución en **dos fases independientes**:
1. **Fase 1**: Planificación de rutas con metaheurísticos (ACO o ALNS)
2. **Fase 2**: Asignación determinística de envíos a vuelos (Min-Cost Flow)

Con framework de experimentación automatizado para análisis estadístico.

---

## 📋 Contenido

- [Quick Start](#quick-start)
- [Algoritmos Utilizados](#algoritmos-utilizados)
- [Arquitectura](#arquitectura)
- [Ejecución](#ejecución)
- [Framework de Experimentación](#framework-de-experimentación)
- [Documentación Completa](#documentación-completa)

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

## 🎯 Algoritmos Utilizados

El sistema ejecuta **AMBOS algoritmos** en cada corrida: **ALNS** y **ACO**. No hay parámetro para seleccionar solo uno.

### ALNS
- Heurística de búsqueda local adaptativa
- Parámetro de semilla: `--semilla-alns` (default: 17)

### ACO
- Metaheurístico basado en colonia de hormigas
- Parámetro de semilla: `--semilla-aco` (default: 17)

### Cómo se ejecutan
- Para cada configuración, ALNS y ACO corren por separado
- `--corridas=N` significa N corridas de ALNS y N corridas de ACO
- Los resultados se guardan en CSV separados por corrida y resumen

---

## 🏗️ Arquitectura

La solución está dividida en dos fases:

1. **Planificación de rutas**: `ACO_RutasPlanner` o `ALNS_RutasPlanner`
2. **Asignación a vuelos**: `MinCostFlowAssigner` dentro de `TwoPhaseOrchestrator`

El flujo completo lo ejecuta `StandardExperimentPipeline` desde `Main`.

---

## 🔧 Ejecución

El sistema está diseñado para **experimentos automatizados**, no simulaciones interactivas:

1. **Carga de Datos**
   - Lee aeropuertos, vuelos y envíos desde `data/input/`
   - Detecta automáticamente fechas y capacidades

2. **Cálculo de Capacidad Diaria**
   - Analiza máxima capacidad del sistema
   - Genera estadísticas por día

3. **Generación de Niveles de Carga**
   - Por defecto: 5 niveles entre 20%-70%
   - Cada nivel → selecciona día histórico más cercano

4. **Ejecución de Experimentos**
   - Para cada nivel de carga:
     - Selecciona día objetivo
     - Ejecuta ALNS N veces
     - Ejecuta ACO N veces
     - Mide: % éxito, maletas asignadas, duración, colapso

5. **Detección de Colapso**
   - Identifica fallos: paquetes sin asignar o llegadas tardías

6. **Exportación de Resultados**
   - CSV Raw: una fila por corrida
   - CSV Resumen: agregado por algoritmo/nivel
   - Listo para análisis estadístico (ANOVA, gráficas)

---

## 📊 Framework de Experimentación

### StandardExperimentPipeline

Orquestador principal que automatiza todo el flujo:

```java
StandardExperimentPipeline pipeline = new StandardExperimentPipeline(
    dataDir,              // Path: directorio de datos
    fechaInicio,          // LocalDate: inicio ventana de vuelos
    diasVuelos,           // int: cantidad de días
    maxEnviosPorArchivo,  // int: 0 = todos
    corridasPorAlgoritmo, // int: cuántas veces ejecutar cada algoritmo
    algoritmos            // List<AlgorithmSpec>: ALNS + ACO
);

StandardExperimentPipeline.PipelineResult resultado = pipeline.ejecutar();
// Retorna: capacidadMaximaDiaria, nivelesObjetivo, rawCsv, summaryCsv
```

### Utilidades de Soporte

## 📊 Métricas y Resultados

Cada corrida guarda:
- algoritmo
- nivel objetivo o fecha seleccionada
- paquetes asignados / no asignados
- porcentaje de éxito
- maletas fuera de plazo
- colapso detectado
- costo total
- tiempo de ejecución

## 📚 Documentación Relacionada

- [data/README.md](data/README.md)
- [ARQUITECTURA_DOS_FASES.md](ARQUITECTURA_DOS_FASES.md)
- [IMPLEMENTACION_DOS_FASES.md](IMPLEMENTACION_DOS_FASES.md)
- [GUIA_DISTRIBUCION_ENVIOS.md](GUIA_DISTRIBUCION_ENVIOS.md)
- [GUIA_GENERADOR_NIVELES_CARGA.md](GUIA_GENERADOR_NIVELES_CARGA.md)
        ├── Paquete.java
        ├── Vuelo.java
        └── ...
```

---

## 📚 Documentación Completa

### Arquitectura y Diseño

- **[ARQUITECTURA_DOS_FASES.md](ARQUITECTURA_DOS_FASES.md)**
  - Descripción técnica detallada de cada fase
  - Interfaces y clases principales
  - Función de costo Min-Cost Flow
  - Ventajas del diseño de dos fases
  - Parámetros de configuración

- **[IMPLEMENTACION_DOS_FASES.md](IMPLEMENTACION_DOS_FASES.md)**
  - Resumen de implementación
  - Comparación: antes vs después
  - 3 ejemplos de uso básicos
  - Próximos pasos sugeridos

### Utilidades

- **[GUIA_DISTRIBUCION_ENVIOS.md](GUIA_DISTRIBUCION_ENVIOS.md)**
  - Cómo agrupar envíos por fecha
  - Encontrar días cercanos a objetivo
  - Estadísticas de distribución
  - 3 ejemplos de uso

- **[GUIA_GENERADOR_NIVELES_CARGA.md](GUIA_GENERADOR_NIVELES_CARGA.md)**
  - Cómo generar niveles de carga
  - Rangos y distribuciones
  - 4 ejemplos prácticos
  - Casos de uso recomendados

### Datos

- **[data/README.md](data/README.md)**
  - Estructura esperada de archivos de entrada
  - Formatos esperados
  - Ejecución y compilación

---

## 🔬 Análisis de Resultados

### Archivos CSV Generados

Después de ejecutar el pipeline, se generan en `data/output/`:

1. **experimentos_raw_TIMESTAMP.csv**
   - Una fila por corrida
   - Columnas: `algoritmo`, `nivel`, `corrida`, `maletas_asignadas`, `no_asignados`, `porcentajeExito`, `duración_ms`, `hayColapso`
   - Listo para ANOVA y gráficas en Python/R/Excel

2. **experimentos_resumen_TIMESTAMP.csv**
   - Agregado por algoritmo y nivel
   - Columnas: `algoritmo`, `nivel`, `promedio_exito`, `desviacion_exito`, `min_exito`, `max_exito`
   - Para comparativas de alto nivel

### Análisis Recomendado

```python
import pandas as pd

# Cargar resultados
df = pd.read_csv('data/output/experimentos_raw_*.csv')

# ANOVA: Comparar algoritmos
from scipy import stats
alns_exito = df[df['algoritmo'] == 'ALNS']['porcentajeExito']
aco_exito = df[df['algoritmo'] == 'ACO']['porcentajeExito']
f_stat, p_value = stats.f_oneway(alns_exito, aco_exito)

# Gráficas
import matplotlib.pyplot as plt
df.boxplot(column='porcentajeExito', by='algoritmo')
plt.show()
```

## 🔍 Troubleshooting

### Error: "No se encontraron archivos"
→ Verifica que `data/input/aeropuertos/`, `data/input/vuelos/` y `data/input/envios/` existan y contengan archivos

### Error: "No hay datos de entrada"
→ Revisa que los archivos de entrada tengan el formato esperado (ver `data/README.md`)

### Pocos paquetes asignados
→ Es normal si los niveles de carga son muy altos o la ventana de vuelos es pequeña
→ Usa `--dias-vuelos` más grande para incluir más vuelos

### Error al compilar
→ Asegúrate de usar Java 8+: `java -version`
→ Usa: `javac -encoding UTF-8 -d out $(find src -name "*.java")`

---

## 📋 Parámetros CLI

| Parámetro | Valor Default | Descripción |
|-----------|---------------|-------------|
| `--data-dir` | `data` | Directorio raíz de datos |
| `--fecha-inicio-vuelos` | `2026-01-02` | Fecha de inicio de la ventana de vuelos |
| `--dias-vuelos` | `0` | Cantidad de días a incluir; `0` significa cargar todos los vuelos disponibles |
| `--max-envios` | `0` (todos) | Límite de envíos por archivo (0 = sin límite) |
| `--corridas` | `10` | Corridas por algoritmo |
| `--semilla-alns` | `17` | Semilla aleatoria para ALNS |
| `--semilla-aco` | `17` | Semilla aleatoria para ACO |

---

## ✅ Validación

```bash
# Compilación
javac -encoding UTF-8 -d out $(find src -name "*.java")

# Ejecución básica
java -cp out tasf.app.Main

# Tests
java -cp out tasf.tests.PlannerTests
```

---

## 🛠️ Desarrollo

### Agregar un Nuevo Metaheurístico

1. Implementar `PlanificadorRutasStrategy`
2. Retornar `Map<String, List<Ruta>>`
3. Agregarlo a `Main.java` en la lista de `AlgorithmSpec`

```java
List.of(
    new StandardExperimentPipeline.AlgorithmSpec("MiAlgoritmo", 
        () -> new tasf.strategy.MiAlgoritmo(semilla))
)
```

### Cambiar Función de Costo

Modificar `MinCostFlowAssigner.calcularCostoAsignacion()`:
```java
private double calcularCostoAsignacion(Paquete paquete, Ruta ruta, Vuelo vuelo, int cargaActualVuelo, Dataset datos, Config_Simulacion config) {
    double horasTransporte = ruta.getHorasTotalesDesde(...);
    double penalizacion = (llegada > plazo) ? 2500 : 0;
    double balanceo = (cargaVuelo / capacidad) * 100;
    return horasTransporte + penalizacion + balanceo;
}
```

---

## 📞 Contacto y Soporte

Para preguntas sobre:
- **Arquitectura**: Ver `ARQUITECTURA_DOS_FASES.md`
- **Utilidades**: Ver `GUIA_*.md` respectivos
- **Datos**: Ver `data/README.md`

**Nota**: La carpeta `src/tasf/examples/` fue eliminada.

---

**Última actualización**: 2026-04-30  
**Estado**: ✅ Totalmente funcional (36 archivos Java fuente, 0 errores de compilación)
