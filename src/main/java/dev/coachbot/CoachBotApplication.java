package dev.coachbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@ConfigurationPropertiesScan   // auto-detects all @ConfigurationProperties classes
public class CoachBotApplication {

    public static void main(String[] args) throws IOException {
        // SQLite requires the parent directory to exist before Spring initialises the DataSource.
        // We create it here, before the application context starts.
        String dataDir = System.getenv().getOrDefault("DATA_DIR", "data");
        Files.createDirectories(Path.of(dataDir));

        SpringApplication.run(CoachBotApplication.class, args);
    }
}
