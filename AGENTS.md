# AGENTS.md

## Project

Java (8+) project — airport logistics route planning with a two-phase architecture: metaheuristic route selection (ACO / ALNS) followed by deterministic feasibility validation. No build tool (Maven/Gradle); plain `javac`.

## Commands

| Step | Command |
|------|---------|
| Compile | `javac -encoding UTF-8 -d out $(find src -name "*.java")` |
| Run | `java -cp out tasf.app.Main` |
| Tests | `java -cp out tasf.tests.PlannerTests` |

Order: compile → run / tests. No separate build tool. `target/` contains stale .class files — ignore it; output goes to `out/`.

## Architecture

**Two-phase pipeline** orchestrated by `StandardExperimentPipeline`:

- **Fase 1** — `PlanificadorRutasStrategy.planificarRutas()` → `Map<String, Ruta>` (one route per package). Implemented by `ALNS_RutasPlanner` and `ACO_RutasPlanner`.
- **Fase 2** — `MinCostFlowAssigner.asignarEnviosAVuelos()` validates each chosen route against `EstadoOperacional` (capacity, temporal windows). Rejects infeasible routes; does **not** re-optimize.

The objective function lives in `PlanificacionUtils.evaluarAsignacion()`:
```
cost = (noAsignados × 10000) + (fueraDePlazo × 2500) + (colapso × 5000) + horasAcumuladas
```

### Key packages

| Package | Role |
|---------|------|
| `tasf.app` | Entry point (`Main`) + CLI parsing |
| `tasf.core` | Domain logic: `Dataset`, `EstadoOperacional`, `RouteFinder`, `PlanificacionUtils`, `DistribucionEnviosPorDia`, `CapacidadDiariaCalculadora`, `ColapsoDetector`, `Solucion` |
| `tasf.strategy` | Interfaces (`PlanificadorRutasStrategy`, `PlanificadorStrategy`, `Asignador`) + `TwoPhaseOrchestrator` |
| `tasf.strategy.alns` | ALNS metaheuristic (`ALNS_RutasPlanner`, `ALNS_Strategy`) |
| `tasf.strategy.aco` | ACO metaheuristic (`ACO_RutasPlanner`, `ACO_Strategy`) |
| `tasf.strategy.flow` | Phase 2 deterministic assigner (`MinCostFlowAsignador`, `MinCostFlowAssigner`, `Asignador`) |
| `tasf.experiments` | `StandardExperimentPipeline` |
| `tasf.io` | Text file loaders for airports, flights, packages |
| `tasf.model` | Domain classes: `Paquete`, `Vuelo`, `Ruta`, `Aeropuerto`, `Tramo` |
| `tasf.config` | `Config_Simulacion` |

## Data

| Path | Encoding | Notes |
|------|----------|-------|
| `data/input/aeropuertos/` | UTF-16 LE | File with "Aeropuerto" in name |
| `data/input/vuelos/planes_vuelo.txt` | UTF-8 | `ID\|origen\|destino\|horario\|capacidad\|...` |
| `data/input/envios/_envios_XXXX_.txt` | UTF-8 | XXXX = ICAO code |
| `data/output/` | — | Gitignored; JSON logs land here |

## CLI defaults (important)

- `--algoritmo=ALNS` → runs ALNS. Use `--algoritmo=ACO` for ACO. **One algorithm per invocation.**
- `--dias-vuelos=3` → loads 3 days of flights. `0` loads **all** flights (~3.1M, ~1095 days).
- `--semilla-alns=17` / `--semilla-aco=17` — default seeds.
- `--fecha-envios` unset → uses `max` (day with highest shipment count).
- `--rango-envios=2026-01-01:2026-01-07` → explicit date range (processes all packages in range).
- `--rango-envios=3-7` → index-based range (days relative to `fechaInicioVuelos`).
- When `--fecha-envios` or `--rango-envios` is used without `--dias-vuelos`, the flight window is auto-calculated to 3 days.

## Adding a new metaheuristic

1. Implement `PlanificadorRutasStrategy` → `Map<String, Ruta>`
2. Register in `Main.java` as a new `AlgorithmSpec` in the pipeline's algorithm list

## Testing

`PlannerTests` is a single-file test harness with manual assertions (no JUnit). Run via `java -cp out tasf.tests.PlannerTests`. Uses synthetic datasets built in code; does **not** require input data files.

## JSON Log Format

Logs are written to `data/output/log_YYYYMMDD_HHMMSS.json` with these sections:

- **metadata**: algorithm, date selection type (`rango`, `fecha_fija`, `dia_maximo`, `rango_indice`, `dia_indice`), selected date display, totals, cost, duration
- **escaneo**: scan info (days scanned, scan time, total shipments) — only in `dia_maximo` mode
- **configuracion**: adaptive config (mode, iterations, ants, evaporation, etc.)
- **diagnosticoFueraDePlazo**: detailed breakdown of late packages with full route — only when there are late packages
- **asignaciones**: list of assigned packages with route and flight details

## Gotchas

- `src/tasf/examples/` is **deprecated** — no longer part of the project.
- `target/` directory contains old compiled classes from a previous setup. Use `out/` only.
- Airport file is UTF-16 LE; all other input files are UTF-8. Wrong encoding = silent parse failures.
- `StandardExperimentPipeline` writes JSON logs with timestamps — old results are not overwritten.
- Package dates in text files are **local time** at the origin airport; they are converted to UTC during loading. Filtering by date uses UTC dates.
