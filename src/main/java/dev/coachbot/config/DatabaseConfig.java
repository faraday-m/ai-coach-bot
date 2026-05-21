package dev.coachbot.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures the data directory exists before Spring Boot initialises the SQLite DataSource.
 * SQLite creates the .db file automatically, but the parent directory must exist first.
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Value("${DATA_DIR:data}")
    private String dataDir;

    @PostConstruct
    public void createDataDirectory() throws IOException {
        Path dir = Path.of(dataDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("Created data directory: {}", dir.toAbsolutePath());
        }
    }
}
