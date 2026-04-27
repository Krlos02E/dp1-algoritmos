#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import codecs
import random
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path


DEFAULT_DATE_MIN = date(2026, 1, 2)
DEFAULT_DATE_MAX = date(2029, 1, 5)
DEFAULT_WINDOW_DAYS = 30
DEFAULT_BLOCKS = 30
DEFAULT_ALGORITHMS = ("ALNS", "ACO")
DEFAULT_MIN_ENVIOS = 200
DEFAULT_MAX_ENVIOS_TOTAL = 800
DEFAULT_RANDOM_SEED = 17

AIRPORT_FILE_RE = re.compile(r"aeropuerto", re.IGNORECASE)
ENVIOS_FILE_RE = re.compile(r"_envios_([A-Z]{4})_\.txt")


@dataclass(frozen=True)
class BlockWindow:
    bloque: int
    sim_start: date
    sim_end: date

    @property
    def flight_start(self) -> date:
        return self.sim_start - timedelta(days=1)


SUMMARY_PATTERNS = {
    "estrategia": re.compile(r"^Estrategia:\s*(.+)$"),
    "asignados": re.compile(r"^Asignados:\s*(\d+)\s*/\s*(\d+)$"),
    "fuera_plazo": re.compile(r"^Fuera de plazo:\s*(\d+)$"),
    "colapso": re.compile(r"^Eventos de colapso:\s*(\d+)$"),
    "promedio": re.compile(r"^Horas promedio de entrega:\s*([0-9]+(?:\.[0-9]+)?)$"),
    "costo": re.compile(r"^Costo total:\s*([0-9]+(?:\.[0-9]+)?)$"),
    "metricas": re.compile(r"^Metricas:\s*(\{.*\})$"),
    "logfile": re.compile(r"^Log file:\s*(.+)$"),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Ejecuta 30 bloques aleatorios de 30 dias con ALNS y ACO y exporta resultados."
    )
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--data-dir", type=Path, default=None)
    parser.add_argument("--output-csv", type=Path, default=None)
    parser.add_argument("--blocks", type=int, default=DEFAULT_BLOCKS)
    parser.add_argument("--window-days", type=int, default=DEFAULT_WINDOW_DAYS)
    parser.add_argument("--seed", type=int, default=12345)
    parser.add_argument("--min-envios", type=int, default=DEFAULT_MIN_ENVIOS)
    parser.add_argument("--max-envios-total", type=int, default=DEFAULT_MAX_ENVIOS_TOTAL)
    parser.add_argument("--random-seed", type=int, default=DEFAULT_RANDOM_SEED)
    parser.add_argument("--compile", action="store_true", help="Compila el proyecto antes de ejecutar")
    parser.add_argument("--dry-run", action="store_true", help="Solo imprime bloques y comandos")
    return parser.parse_args()


def sh_quote(path: Path) -> str:
    return "'" + str(path).replace("'", "'\\''") + "'"


def find_airport_file(data_dir: Path) -> Path:
    airport_dir = data_dir / "input" / "aeropuertos"
    for candidate in sorted(airport_dir.iterdir()):
        if candidate.is_file() and AIRPORT_FILE_RE.search(candidate.name):
            return candidate
    raise FileNotFoundError(f"No se encontro archivo de aeropuertos en {airport_dir}")


def load_airport_codes(data_dir: Path) -> set[str]:
    airport_file = find_airport_file(data_dir)
    codes: set[str] = set()
    with codecs.open(airport_file, "r", "utf-16") as fh:
        for raw in fh:
            line = raw.strip()
            if not line:
                continue
            match = re.match(r"^\s*\d+\s+([A-Z]{4})\s+.*?\s+([+-]\d+)\s+(\d+)\s+Latitude.*$", line)
            if match:
                codes.add(match.group(1))
    return codes


def load_daily_valid_envio_counts(data_dir: Path) -> dict[date, int]:
    airport_codes = load_airport_codes(data_dir)
    counts: dict[date, int] = {}
    envios_dir = data_dir / "input" / "envios"

    for candidate in sorted(envios_dir.iterdir()):
        if not candidate.is_file():
            continue
        match = ENVIOS_FILE_RE.match(candidate.name)
        if not match or match.group(1) not in airport_codes:
            continue

        with candidate.open("r", encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line:
                    continue

                parts = line.split("-")
                if len(parts) < 5:
                    continue

                try:
                    envio_date = date.fromisoformat(f"{parts[1][0:4]}-{parts[1][4:6]}-{parts[1][6:8]}")
                except ValueError:
                    continue

                if ":" in parts[2]:
                    destination = parts[3]
                else:
                    if len(parts) < 6:
                        continue
                    destination = parts[4]

                if destination not in airport_codes:
                    continue

                counts[envio_date] = counts.get(envio_date, 0) + 1

    return counts


def run(cmd: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        cwd=str(cwd),
        text=True,
        capture_output=True,
        check=False,
    )


def compile_project(repo_root: Path) -> None:
    out_dir = repo_root / "out"
    out_dir.mkdir(exist_ok=True)
    cmd = ["bash", "-lc", f"javac -d {sh_quote(out_dir)} $(find src/tasf -name '*.java')"]
    completed = run(cmd, cwd=repo_root)
    if completed.returncode != 0:
        raise RuntimeError(
            "Falló la compilacion.\nSTDOUT:\n" + completed.stdout + "\nSTDERR:\n" + completed.stderr
        )


def choose_non_overlapping_windows(
    rng: random.Random,
    blocks: int,
    window_days: int,
    date_min: date,
    date_max: date,
    daily_counts: dict[date, int],
    min_envios: int,
) -> list[BlockWindow]:
    last_start = date_max - timedelta(days=window_days - 1)
    if last_start < date_min:
        raise ValueError("El rango del dataset no alcanza para una ventana de ese tamaño.")

    total_starts = (last_start - date_min).days + 1
    if total_starts < blocks * window_days:
        raise RuntimeError(
            f"No alcanza el rango para {blocks} bloques de {window_days} dias sin superponer ventanas. "
            f"Hay {total_starts} fechas posibles de inicio y se requieren al menos {blocks * window_days}."
        )

    chunk = total_starts // blocks
    if chunk < window_days:
        raise RuntimeError(
            f"No alcanza el rango para {blocks} bloques de {window_days} dias sin superponer ventanas."
        )

    chosen: list[BlockWindow] = []
    for bloque in range(blocks):
        segment_start = date_min + timedelta(days=bloque * chunk)
        segment_end = last_start if bloque == blocks - 1 else date_min + timedelta(days=((bloque + 1) * chunk) - 1)
        latest_start = segment_end - timedelta(days=window_days - 1)
        if latest_start < segment_start:
            raise RuntimeError(
                f"El segmento {bloque + 1} no tiene espacio para una ventana de {window_days} dias."
            )

        candidate_offsets = list(range(0, (latest_start - segment_start).days + 1))
        rng.shuffle(candidate_offsets)

        sim_start = None
        sim_end = None
        for offset_days in candidate_offsets:
            candidate_start = segment_start + timedelta(days=offset_days)
            candidate_end = candidate_start + timedelta(days=window_days - 1)
            total_filtrados = sum(
                daily_counts.get(candidate_start + timedelta(days=delta), 0)
                for delta in range(window_days)
            )
            if total_filtrados >= min_envios:
                sim_start = candidate_start
                sim_end = candidate_end
                break

        if sim_start is None or sim_end is None:
            raise RuntimeError(
                f"No se encontro una ventana valida para el bloque {bloque + 1} con al menos {min_envios} envios."
            )

        chosen.append(BlockWindow(bloque=bloque + 1, sim_start=sim_start, sim_end=sim_end))

    chosen.sort(key=lambda block: (block.sim_start, block.bloque))
    return [BlockWindow(bloque=index + 1, sim_start=block.sim_start, sim_end=block.sim_end) for index, block in enumerate(chosen)]


def parse_summary(stdout: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in stdout.splitlines():
        line = line.strip()
        if not line:
            continue

        for key, pattern in SUMMARY_PATTERNS.items():
            match = pattern.match(line)
            if not match:
                continue

            if key == "asignados":
                result["asignados"] = match.group(1)
                result["total_paquetes"] = match.group(2)
            elif key == "estrategia":
                result["estrategia"] = match.group(1)
            elif key == "fuera_plazo":
                result["fuera_plazo"] = match.group(1)
            elif key == "colapso":
                result["colapso"] = match.group(1)
            elif key == "promedio":
                result["horas_promedio_entrega"] = match.group(1)
            elif key == "costo":
                result["costo_total"] = match.group(1)
            elif key == "metricas":
                result["metricas"] = match.group(1)
            elif key == "logfile":
                result["log_file"] = match.group(1)
            break

    missing = [key for key in ("costo_total", "asignados", "fuera_plazo", "colapso") if key not in result]
    if missing:
        raise RuntimeError(
            "No se pudo parsear la salida del programa. Faltan: " + ", ".join(missing) + "\n" + stdout
        )
    return result


def run_java_experiment(
    repo_root: Path,
    data_dir: Path,
    block: BlockWindow,
    window_days: int,
    algorithm: str,
    min_envios: int,
    max_envios_total: int,
    random_seed: int,
) -> dict[str, str]:
    cmd = [
        "java",
        "-cp",
        str(repo_root / "out"),
        "tasf.app.Main",
        f"--data-dir={data_dir}",
        f"--min-envios={min_envios}",
        f"--max-envios-total={max_envios_total}",
        f"--random-seed={random_seed}",
        f"--fecha={block.flight_start.isoformat()}",
        f"--dias-vuelos={window_days + 1}",
        f"--dias-simulacion={window_days}",
        f"--algoritmo={algorithm}",
    ]

    completed = run(cmd, cwd=repo_root)
    if completed.returncode != 0:
        raise RuntimeError(
            f"Falló la ejecución de {algorithm} en el bloque {block.bloque} ({block.sim_start}..{block.sim_end}).\n"
            f"STDOUT:\n{completed.stdout}\nSTDERR:\n{completed.stderr}"
        )

    parsed = parse_summary(completed.stdout)
    parsed.update(
        {
            "bloque": str(block.bloque),
            "sim_start": block.sim_start.isoformat(),
            "sim_end": block.sim_end.isoformat(),
            "flight_start": block.flight_start.isoformat(),
            "algorithm": algorithm,
        }
    )
    return parsed


def main() -> int:
    args = parse_args()
    repo_root = args.repo_root.resolve()
    data_dir = (args.data_dir or (repo_root / "data")).resolve()
    output_csv = (args.output_csv or (repo_root / "output" / "dca_resultados.csv")).resolve()
    output_csv.parent.mkdir(parents=True, exist_ok=True)

    daily_counts = load_daily_valid_envio_counts(data_dir)

    if args.compile:
        compile_project(repo_root)

    rng = random.Random(args.seed)
    blocks = choose_non_overlapping_windows(
        rng=rng,
        blocks=args.blocks,
        window_days=args.window_days,
        date_min=DEFAULT_DATE_MIN,
        date_max=DEFAULT_DATE_MAX,
        daily_counts=daily_counts,
        min_envios=args.min_envios,
    )

    fieldnames = [
        "bloque",
        "sim_start",
        "sim_end",
        "flight_start",
        "algorithm",
        "estrategia",
        "log_file",
        "asignados",
        "total_paquetes",
        "fuera_plazo",
        "colapso",
        "horas_promedio_entrega",
        "costo_total",
        "metricas",
    ]

    print(f"Bloques seleccionados: {len(blocks)}")
    print(f"Ventana simulacion: {args.window_days} dias")
    print(f"Rango usado: {DEFAULT_DATE_MIN.isoformat()} .. {DEFAULT_DATE_MAX.isoformat()}")
    print(f"Salida CSV: {output_csv}")

    rows: list[dict[str, str]] = []
    for block in blocks:
        print(f"Bloque {block.bloque}: {block.sim_start.isoformat()} .. {block.sim_end.isoformat()}")
        for algorithm in DEFAULT_ALGORITHMS:
            cmd_preview = (
                f"java -cp {repo_root / 'out'} tasf.app.Main --data-dir={data_dir} --min-envios={args.min_envios} "
                f"--max-envios-total={args.max_envios_total} --random-seed={args.random_seed} "
                f"--fecha={block.flight_start.isoformat()} --dias-vuelos={args.window_days + 1} "
                f"--dias-simulacion={args.window_days} --algoritmo={algorithm}"
            )
            print(f"  {algorithm}: {cmd_preview}")
            if args.dry_run:
                continue

            parsed = run_java_experiment(
                repo_root,
                data_dir,
                block,
                args.window_days,
                algorithm,
                args.min_envios,
                args.max_envios_total,
                args.random_seed,
            )
            rows.append(parsed)
            print(
                "    costo=" + parsed["costo_total"]
                + " | asignados=" + parsed["asignados"]
                + " | fuera_plazo=" + parsed["fuera_plazo"]
                + " | colapso=" + parsed["colapso"]
            )

    if not args.dry_run:
        with output_csv.open("w", newline="", encoding="utf-8") as fh:
            writer = csv.DictWriter(fh, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)

        print(f"CSV generado con {len(rows)} filas: {output_csv}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())