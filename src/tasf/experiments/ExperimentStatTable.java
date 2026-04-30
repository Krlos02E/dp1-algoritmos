package tasf.experiments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Tabla de resultados estadisticos para analisis en Python, R o Excel.
 */
public final class ExperimentStatTable {
    private final List<ExperimentStatRecord> rows;

    public ExperimentStatTable(List<ExperimentStatRecord> rows) {
        Objects.requireNonNull(rows, "rows no puede ser null");
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
    }

    public List<ExperimentStatRecord> getRows() {
        return rows;
    }

    /**
     * Crea una tabla estadistica minima a partir de la salida del ExperimentRunner.
     */
    public static ExperimentStatTable fromExperimentTable(ExperimentRunner.ExperimentTable table) {
        Objects.requireNonNull(table, "table no puede ser null");

        List<ExperimentStatRecord> out = new ArrayList<>();
        for (ExperimentRunner.RunResult row : table.getRows()) {
            out.add(new ExperimentStatRecord(
                    row.algoritmo,
                    row.nivelCargaObjetivoMaletas,
                    row.corrida,
                    row.porcentajeExito
            ));
        }

        return new ExperimentStatTable(out);
    }

    /**
     * Exporta lineas CSV con encabezado.
     */
    public List<String> toCsvLines() {
        List<String> csv = new ArrayList<>();
        csv.add("algoritmo,nivel,corrida,porcentajeExito");

        for (ExperimentStatRecord row : rows) {
            csv.add(String.format(
                    Locale.ROOT,
                    "%s,%d,%d,%.6f",
                    escapeCsv(row.getAlgoritmo()),
                    row.getNivel(),
                    row.getCorrida(),
                    row.getPorcentajeExito()
            ));
        }

        return csv;
    }

    /**
     * Escribe el CSV a archivo listo para consumir por Python/R/Excel.
     */
    public void writeCsv(Path outputCsv) throws IOException {
        Objects.requireNonNull(outputCsv, "outputCsv no puede ser null");

        Path parent = outputCsv.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.write(outputCsv, toCsvLines(), StandardCharsets.UTF_8);
    }

    private static String escapeCsv(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
