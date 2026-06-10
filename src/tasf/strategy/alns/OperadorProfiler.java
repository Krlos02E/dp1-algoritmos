package tasf.strategy.alns;

import java.util.*;

public class OperadorProfiler {

    private final Map<String, Long> destroyTotalTimeNs = new LinkedHashMap<>();
    private final Map<String, Integer> destroyCount = new LinkedHashMap<>();
    private final Map<String, Long> repairTotalTimeNs = new LinkedHashMap<>();
    private final Map<String, Integer> repairCount = new LinkedHashMap<>();

    public void registrarDestroy(String nombre, long tiempoNs) {
        destroyTotalTimeNs.merge(nombre, tiempoNs, Long::sum);
        destroyCount.merge(nombre, 1, Integer::sum);
    }

    public void registrarRepair(String nombre, long tiempoNs) {
        repairTotalTimeNs.merge(nombre, tiempoNs, Long::sum);
        repairCount.merge(nombre, 1, Integer::sum);
    }

    public String generarReporte() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== REPORTE DE PROFILING ALNS ===\n");
        sb.append(String.format("%-25s | %-6s | %-10s | %-10s%n", "Operador", "Calls", "Total(ms)", "Avg(ms)"));
        sb.append("-".repeat(60)).append("\n");

        Map<String, double[]> promedios = new LinkedHashMap<>();

        for (String op : destroyTotalTimeNs.keySet()) {
            long totalNs = destroyTotalTimeNs.getOrDefault(op, 0L);
            int count = destroyCount.getOrDefault(op, 0);
            if (count > 0) {
                promedios.put("D:" + op, new double[]{count, totalNs / 1_000_000.0, (totalNs / 1_000_000.0) / count});
            }
        }

        for (String op : repairTotalTimeNs.keySet()) {
            long totalNs = repairTotalTimeNs.getOrDefault(op, 0L);
            int count = repairCount.getOrDefault(op, 0);
            if (count > 0) {
                promedios.put("R:" + op, new double[]{count, totalNs / 1_000_000.0, (totalNs / 1_000_000.0) / count});
            }
        }

        promedios.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue()[2], e1.getValue()[2]))
                .forEach(e -> {
                    String op = e.getKey();
                    double[] vals = e.getValue();
                    sb.append(String.format("%-25s | %6d | %10.1f | %10.2f%n",
                            op, (int) vals[0], vals[1], vals[2]));
                });

        sb.append("-".repeat(60)).append("\n");
        return sb.toString();
    }

    public Map<String, Double> getMetricas() {
        Map<String, Double> metricas = new LinkedHashMap<>();
        for (String op : destroyTotalTimeNs.keySet()) {
            int count = destroyCount.getOrDefault(op, 0);
            long totalNs = destroyTotalTimeNs.getOrDefault(op, 0L);
            if (count > 0) {
                metricas.put("avgDestroy_" + op + "_ms", (totalNs / 1_000_000.0) / count);
            }
        }
        for (String op : repairTotalTimeNs.keySet()) {
            int count = repairCount.getOrDefault(op, 0);
            long totalNs = repairTotalTimeNs.getOrDefault(op, 0L);
            if (count > 0) {
                metricas.put("avgRepair_" + op + "_ms", (totalNs / 1_000_000.0) / count);
            }
        }
        return metricas;
    }
}
