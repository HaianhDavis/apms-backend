package com.apms.domain.admin.service;

import com.apms.domain.admin.dto.SystemHealthResponse;
import com.apms.domain.admin.dto.SystemHealthResponse.ServiceHealth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final DataSource dataSource;
    private final MongoTemplate mongoTemplate;
    private final Driver neo4jDriver;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public SystemHealthResponse checkHealth() {
        List<ServiceHealth> services = new ArrayList<>();

        // API Server — always UP (we're executing this code)
        services.add(ServiceHealth.builder()
                .name("API Server")
                .status("UP")
                .latencyMs(0L)
                .lastChecked(LocalDateTime.now().format(FORMATTER))
                .build());

        // SQL Server
        services.add(checkSqlServer());

        // MongoDB
        services.add(checkMongoDB());

        // Neo4j
        services.add(checkNeo4j());

        return SystemHealthResponse.builder().services(services).build();
    }

    private ServiceHealth checkSqlServer() {
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("SELECT 1").execute();
            long latency = System.currentTimeMillis() - start;
            return ServiceHealth.builder()
                    .name("SQL Server")
                    .status("UP")
                    .latencyMs(latency)
                    .lastChecked(LocalDateTime.now().format(FORMATTER))
                    .build();
        } catch (Exception e) {
            log.warn("SQL Server health check failed: {}", e.getMessage());
            return ServiceHealth.builder()
                    .name("SQL Server")
                    .status("DOWN")
                    .latencyMs(null)
                    .lastChecked(LocalDateTime.now().format(FORMATTER))
                    .errorReason(e.getMessage())
                    .build();
        }
    }

    private ServiceHealth checkMongoDB() {
        long start = System.currentTimeMillis();
        try {
            mongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
            long latency = System.currentTimeMillis() - start;
            return ServiceHealth.builder()
                    .name("MongoDB")
                    .status("UP")
                    .latencyMs(latency)
                    .lastChecked(LocalDateTime.now().format(FORMATTER))
                    .build();
        } catch (Exception e) {
            log.warn("MongoDB health check failed: {}", e.getMessage());
            return ServiceHealth.builder()
                    .name("MongoDB")
                    .status("DOWN")
                    .latencyMs(null)
                    .lastChecked(LocalDateTime.now().format(FORMATTER))
                    .errorReason(e.getMessage())
                    .build();
        }
    }

    private ServiceHealth checkNeo4j() {
        long start = System.currentTimeMillis();
        try (Session session = neo4jDriver.session()) {
            session.run("RETURN 1").consume();
            long latency = System.currentTimeMillis() - start;
            return ServiceHealth.builder()
                    .name("Neo4j")
                    .status("UP")
                    .latencyMs(latency)
                    .lastChecked(LocalDateTime.now().format(FORMATTER))
                    .build();
        } catch (Exception e) {
            log.warn("Neo4j health check failed: {}", e.getMessage());
            return ServiceHealth.builder()
                    .name("Neo4j")
                    .status("DOWN")
                    .latencyMs(null)
                    .lastChecked(LocalDateTime.now().format(FORMATTER))
                    .errorReason(e.getMessage())
                    .build();
        }
    }
}
