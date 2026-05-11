# Estructura de Datos

Este proyecto usa la siguiente estructura organizada de datos:

## Entrada (Input)

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

## Salida (Output)

Aquí se guardan los resultados de las ejecuciones:

```
data/output/
└── log_YYYYMMDD_HHMMSS.json
```

**Archivos generados**:
- `log_*.json`: Log completo de la corrida con metadata, configuración, diagnóstico y asignaciones.

Cada log JSON contiene:
- **metadata**: algoritmo, tipo de selección de fecha, fecha seleccionada, métricas (maletas totales/asignadas, pedidos, colapso, costo, duración)
- **escaneo**: información del escaneo de días (solo en modo `--fecha-envios=max`)
- **configuracion**: parámetros adaptativos usados (iteraciones ALNS/ACO, hormigas, maxRutas, evaporación, etc.)
- **diagnosticoFueraDePlazo**: detalle de cada paquete fuera de plazo con ruta completa y retraso (solo si hay paquetes fuera de plazo)
- **asignaciones**: lista de paquetes asignados con su ruta, vuelos, creación, deadline y tiempos

---

## Ejecutar

### Opción A: Uso Estándar

```bash
java -cp out tasf.app.Main
```

Ejecuta el pipeline con configuración por defecto:
- Datos de `data/`
- Carga 3 días de vuelos (`--dias-vuelos=3` por defecto).
- Usa el día con más envíos (`--fecha-envios=max`).
- Ejecuta ALNS con semilla 17.
- Genera un log JSON en `data/output/`.

### Opción B: Comandos Personalizados

```bash
# Cambiar directorio de datos
java -cp out tasf.app.Main --data-dir=/ruta/a/datos

# Cambiar algoritmo
java -cp out tasf.app.Main --algoritmo=ACO

# Cambiar ventana de vuelos (7 días desde 2026-02-01)
java -cp out tasf.app.Main \
  --fecha-inicio-vuelos=2026-02-01 \
  --dias-vuelos=7

# Evaluar el día más cargado
java -cp out tasf.app.Main --fecha-envios=max

# Evaluar una fecha concreta
java -cp out tasf.app.Main --fecha-envios=2026-01-06

# Evaluar un día por índice relativo
java -cp out tasf.app.Main --fecha-envios=5

# Rango de fechas explícito
java -cp out tasf.app.Main --rango-envios=2026-01-01:2026-01-07

# Rango por índice numérico
java -cp out tasf.app.Main --rango-envios=3-7

# Cargar todos los vuelos (~1095 días)
java -cp out tasf.app.Main --dias-vuelos=0 --fecha-envios=max

# Cambiar semillas de aleatoriedad
java -cp out tasf.app.Main \
  --semilla-alns=42 \
  --semilla-aco=99

# Limitar envíos por archivo
java -cp out tasf.app.Main --max-envios=100
```

---

## Parámetros CLI Disponibles

| Parámetro | Valor Default | Descripción |
|-----------|---------------|-------------|
| `--data-dir` | `data` | Directorio raíz con input/ y output/ |
| `--algoritmo` | `ALNS` | Algoritmo: `ALNS` o `ACO` |
| `--fecha-inicio-vuelos` | `2026-01-01` | Fecha inicial de la ventana de vuelos |
| `--dias-vuelos` | `3` | Cantidad de días a cargar; `0` = cargar todos disponibles (~1095 días) |
| `--max-envios` | `0` (todos) | Límite de envíos por archivo; `0` = sin límite |
| `--fecha-envios` | `max` | Selecciona un día: índice (`5`), fecha (`2026-01-06`), o `max` |
| `--duracion-envios` | `1` | Días consecutivos de envíos |
| `--rango-envios` | - | Rango: `2026-01-01:2026-01-07` o índice `3-7` |
| `--semilla-alns` | `17` | Semilla aleatoria para ALNS |
| `--semilla-aco` | `17` | Semilla aleatoria para ACO |

**Nota**: cuando se usa `--fecha-envios` o `--rango-envios` sin `--dias-vuelos`, la ventana de vuelos se calcula automáticamente a 3 días.

---

## Qué Hace Exactamente una Ejecución

Cuando ejecutas `java -cp out tasf.app.Main`:

1. **Lee CLI** y arma el `StandardExperimentPipeline`
2. **Carga datos** desde `data/input/`:
   - Aeropuertos (OACI, husos horarios)
   - Vuelos: 3 días por defecto, o todos si `--dias-vuelos=0`
   - Paquetes: todos los archivos de `data/input/envios/`
3. **Determina fechas de envío**:
   - **Sin `--fecha-envios`** → Usa `max` (día con más paquetes)
   - **Con `--fecha-envios=max`** → Escanea y usa el día con máxima cantidad de paquetes
   - **Con `--fecha-envios=YYYY-MM-DD`** → Usa ese día específico
   - **Con `--fecha-envios=N`** → Usa el día N relativo a `fechaInicioVuelos`
   - **Con `--rango-envios=A:B`** → Procesa todos los paquetes del rango
4. **Calcula ventana de vuelos** centrada en las fechas de envío
5. **Ejecuta el algoritmo** seleccionado (ALNS o ACO):
   - Fase 1: planifica rutas para cada paquete
   - Fase 2: valida factibilidad operacional
6. **Evalúa resultados**:
   - ¿Se asignaron todos los paquetes?
   - ¿Todos llegan dentro del plazo?
   - ¿Detecta colapso del sistema?
7. **Exporta JSON** a `data/output/log_YYYYMMDD_HHMMSS.json`

---

## Validación

```bash
# Compilar
javac -encoding UTF-8 -d out $(find src -name "*.java")

# Ejecutar tests
java -cp out tasf.tests.PlannerTests
```

---

## Troubleshooting

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

### Error de compilación
Asegúrate de tener Java 8+ instalado: `java -version`
