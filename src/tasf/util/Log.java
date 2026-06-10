package tasf.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Log {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static PrintWriter fileWriter;
    private static Path logFilePath;
    private static boolean initialized = false;

    private Log() {}

    public static synchronized void init(Path outputDir) {
        if (initialized) return;
        try {
            Files.createDirectories(outputDir);
            String stamp = LocalDateTime.now().format(FILE_TS);
            logFilePath = outputDir.resolve("log_detalle_" + stamp + ".txt");
            fileWriter = new PrintWriter(Files.newBufferedWriter(logFilePath, StandardCharsets.UTF_8));
            initialized = true;
        } catch (IOException e) {
            System.err.println("[WARN] No se pudo crear archivo de log detallado: " + e.getMessage());
        }
    }

    public static void info(String msg) {
        System.out.println(msg);
    }

    public static synchronized void detail(String msg) {
        if (!initialized) {
            init(Path.of("data/output"));
        }
        if (fileWriter != null) {
            fileWriter.println(msg);
            fileWriter.flush();
        }
    }

    public static synchronized void close() {
        if (fileWriter != null) {
            fileWriter.close();
        }
        initialized = false;
    }

    public static Path getLogFilePath() {
        return logFilePath;
    }
}
