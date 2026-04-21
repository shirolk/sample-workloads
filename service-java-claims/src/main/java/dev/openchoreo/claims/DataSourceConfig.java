package dev.openchoreo.claims;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${db.password.file:/secrets/db-password}")
    private String passwordFilePath;

    @Bean
    @Primary
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(readPassword());
        HikariDataSource ds = new HikariDataSource(config);
        startPasswordWatcher(ds);
        return ds;
    }

    private String readPassword() {
        try {
            return Files.readString(Path.of(passwordFilePath)).trim();
        } catch (IOException e) {
            log.warn("Could not read password file {}: {}", passwordFilePath, e.getMessage());
            return "";
        }
    }

    private void startPasswordWatcher(HikariDataSource ds) {
        Path file = Path.of(passwordFilePath);
        Path dir = file.getParent();
        Thread watcher = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
                log.info("Watching {} for password rotation", dir);
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = ws.take();
                    boolean changed = key.pollEvents().stream().anyMatch(e -> {
                        Path changed1 = dir.resolve((Path) e.context());
                        return changed1.getFileName().equals(file.getFileName())
                                || e.context().toString().startsWith("..");
                    });
                    if (changed) {
                        String newPassword = readPassword();
                        ds.getHikariConfigMXBean().setPassword(newPassword);
                        ds.getHikariPoolMXBean().softEvictConnections();
                        log.info("Password rotated — connections soft-evicted");
                    }
                    key.reset();
                }
            } catch (IOException | InterruptedException e) {
                log.warn("Password watcher stopped: {}", e.getMessage());
            }
        }, "password-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }
}
