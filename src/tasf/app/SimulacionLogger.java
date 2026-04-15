package tasf.app;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class SimulacionLogger implements AutoCloseable {
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path filePath;
    private final BufferedWriter writer;

    private SimulacionLogger(Path filePath, BufferedWriter writer) {
        this.filePath = filePath;
        this.writer = writer;
    }

    public static SimulacionLogger crear(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String fileName = "simulacion_" + LocalDateTime.now().format(FILE_FORMAT) + ".log";
        Path filePath = outputDir.resolve(fileName);
        BufferedWriter writer = Files.newBufferedWriter(
                filePath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        return new SimulacionLogger(filePath, writer);
    }

    public Path getFilePath() {
        return filePath;
    }

    public synchronized void log(String message) {
        String line = "[" + LocalDateTime.now().format(TS_FORMAT) + "] " + message;
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Error escribiendo log de simulacion", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
