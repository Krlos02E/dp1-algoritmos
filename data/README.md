# Estructura de Datos

Este proyecto usa la siguiente estructura organizada de datos:

## 📁 Entrada (Input)

### aeropuertos/
Coloca aquí el archivo de aeropuertos con la palabra "Aeropuerto" en el nombre.

**Archivo esperado**:
```
c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt
```

**Formato**: UTF-16 LE, contiene códigos OACI e información de husos horarios.

### vuelos/
Coloca aquí el archivo de planes de vuelo.

**Archivo esperado**:
```
planes_vuelo.txt
```

**Formato**: UTF-8, una línea por vuelo. Estructura: `ID|origen|destino|horario|capacidad|...`

### envios/
Coloca aquí archivos de envíos por aeropuerto origen.

**Formato de nombre**: `_envios_XXXX_.txt` donde `XXXX` = código OACI

**Ejemplos**:
```
_envios_SKBO_.txt
_envios_EHAM_.txt
_envios_SCEL_.txt
_envios_SPIM_.txt
```

**Formato**: UTF-8, una línea por envío. Estructura: `ID|destino|cantidadMaletas|fechaEntrega|...`

---

## 📁 Salida (Output)

Aquí se guardan los resultados de las ejecuciones:

```
data/output/
├── experimentos_raw_YYYYMMDD_HHmmss.csv
├── experimentos_resumen_YYYYMMDD_HHmmss.csv
└── ...
```

**Archivos generados**:
- `experimentos_raw_*.csv`: Una fila por corrida (algoritmo, nivel, métricas)
- `experimentos_resumen_*.csv`: Resumen agregado por nivel

---

## 🚀 Ejecutar

### Opción A: Uso Estándar

```bash
java -cp out tasf.app.Main
```

Ejecuta el pipeline con configuración por defecto:
- Datos de `data/` 
- **Carga TODOS los vuelos disponibles** (`--dias-vuelos=0` por defecto)
- Ejecuta ambos algoritmos (ALNS + ACO) con 10 corridas cada uno
- Genera 5 niveles de carga (20%-70% del máximo paquetes diarios)
- Para cada nivel: busca el día histórico con cantidad de paquetes más cercana
- Exporta CSV a `data/output/`

**⚠️ Nota Importante**: El sistema SIEMPRE ejecuta AMBOS algoritmos. No hay opción para usar solo uno.

### Opción B: Comandos Personalizados

```bash
# Cambiar directorio de datos
java -cp out tasf.app.Main --data-dir=/ruta/a/datos

# Cambiar ventana de vuelos (7 días desde 2026-02-01)
java -cp out tasf.app.Main \
  --fecha-inicio-vuelos=2026-02-01 \
  --dias-vuelos=7

# Evaluar un día completo de envíos (el más cargado)
java -cp out tasf.app.Main \
  --corridas=1 \
  --max-envios=0 \
  --fecha-envios=max

# Evaluar una fecha concreta
java -cp out tasf.app.Main \
  --corridas=1 \
  --max-envios=0 \
  --fecha-envios=2026-11-02

# Barrer porcentajes de paquetes hasta encontrar el primero que cumpla plazo
java -cp out tasf.app.Main \
  --corridas=1 \
  --max-envios=0 \
  --fecha-envios=max \
  --barrer-porcentaje-envios \
  --porcentaje-envios-inicial=100 \
  --porcentaje-envios-minimo=10 \
  --paso-porcentaje-envios=5

# Cambiar número de corridas
java -cp out tasf.app.Main --corridas=20

# Cambiar semillas de aleatoriedad
java -cp out tasf.app.Main \
  --semilla-alns=42 \
  --semilla-aco=99

# Limitar envíos por archivo
java -cp out tasf.app.Main --max-envios=100
```

---

## 📋 Parámetros CLI Disponibles

| Parámetro | Valor Default | Descripción |
|-----------|---------------|-------------|
| `--data-dir` | `data` | Directorio raíz con input/ y output/ |
| `--fecha-inicio-vuelos` | `2026-01-02` | Fecha inicial de la ventana de vuelos |
| `--dias-vuelos` | `0` | Cantidad de días a cargar; `0` = cargar todos disponibles (~1095 días) |
| `--max-envios` | `0` (todos) | Límite de envíos por archivo; `0` = sin límite |
| `--corridas` | `10` | Número de corridas **por algoritmo** (ALNS + ACO cada uno) |
| `--fecha-envios` | - | Selecciona un día específico; `max` = día con más paquetes |
| `--barrer-porcentaje-envios` | desactivado | Activa barrido descendente de porcentaje de paquetes |
| `--porcentaje-envios-inicial` | `100` | Porcentaje inicial del barrido (100% = todos los paquetes del día) |
| `--porcentaje-envios-minimo` | `10` | Porcentaje mínimo (parar cuando se alcance) |
| `--paso-porcentaje-envios` | `5` | Paso de reducción entre iteraciones |
| `--semilla-alns` | `17` | Semilla aleatoria para ALNS |
| `--semilla-aco` | `17` | Semilla aleatoria para ACO |

---

## 🎯 Qué Hace Exactamente una Ejecución

Cuando ejecutas `java -cp out tasf.app.Main`:

1. **Lee CLI** y arma el `StandardExperimentPipeline`
2. **Carga datos** desde `data/input/`:
   - Aeropuertos (OACI, husos horarios)
   - Vuelos: Si `--dias-vuelos=0` → carga ~1095 días (3.1M vuelos)
   - Paquetes: todos los archivos de `data/input/envios/`
3. **Calcula estadísticas** de envíos por día
4. **Decide qué evaluar**:
   - **Sin `--fecha-envios`** → Genera 5 niveles de carga (20%-70% del máximo paquetes diarios) y para cada nivel busca el día histórico con cantidad de paquetes más cercana
   - **Con `--fecha-envios=max`** → Usa el día con máxima cantidad de paquetes
   - **Con `--fecha-envios=YYYY-MM-DD`** → Usa ese día específico
   - **Con `--barrer-porcentaje-envios`** → Reduce progresivamente el porcentaje de paquetes del día seleccionado
5. **Ejecuta los algoritmos**:
   - Para cada configuración (nivel o día):
     - Ejecuta **ALNS** N veces (N = `--corridas`)
     - Ejecuta **ACO** N veces (N = `--corridas`)
     - Comparte entrada (vuelos, paquetes del día/porcentaje)
6. **Evalúa resultados** para cada corrida:
   - ¿Se asignaron todos los paquetes?
   - ¿Todos llegan dentro del plazo?
   - ¿Detecta colapso del sistema?
7. **Exporta CSV** a `data/output/`:
   - `experimentos_raw_*.csv` → Una fila por corrida (todos los detalles)
   - `experimentos_resumen_*.csv` → Agregado por nivel/algoritmo

---

## ✅ Validación

```bash
# Compilar
javac -encoding UTF-8 -d out $(find src -name "*.java")

# Ejecutar tests
java -cp out tasf.tests.PlannerTests
```

---

## 🔍 Troubleshooting

### Error: "No se encontraron archivos"
Verifica que existan:
- `data/input/aeropuertos/` con archivo de aeropuertos
- `data/input/vuelos/planes_vuelo.txt`
- `data/input/envios/` con archivos `_envios_XXXX_.txt`

### Error: "No hay datos de entrada"
Verifica formatos de archivo:
- Aeropuertos: UTF-16 LE
- Vuelos: UTF-8
- Envíos: UTF-8

### Pocos paquetes asignados
Es normal si la ventana de vuelos es pequeña. Soluciones:
- Usa `--dias-vuelos=0` para cargar todos los vuelos disponibles
- Usa `--fecha-envios=max` para evaluar el día más cargado
- Usa `--barrer-porcentaje-envios` para reducir progresivamente la muestra

### Error de compilación
Asegúrate de tener Java 8+ instalado: `java -version`
