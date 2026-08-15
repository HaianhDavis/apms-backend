package com.apms.domain.admin.service;

import com.apms.domain.admin.dto.SystemHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;
    private final Neo4jClient neo4jClient;

    public SystemHealthResponse checkHealth() {
        List<SystemHealthResponse.ServiceHealthDto> services = new ArrayList<>();
        services.add(check("SQL Server", this::pingSqlServer));
        services.add(check("MongoDB", this::pingMongoDb));
        services.add(check("Neo4j", this::pingNeo4j));
        return SystemHealthResponse.builder().services(services).build();
    }

    private SystemHealthResponse.ServiceHealthDto check(String name, Supplier<Void> ping) {
        long start = System.nanoTime();
        try {
            ping.get();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return SystemHealthResponse.ServiceHealthDto.builder()
                    .name(name)
                    .status("UP")
                    .latencyMs(latencyMs)
                    .lastChecked(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return SystemHealthResponse.ServiceHealthDto.builder()
                    .name(name)
                    .status("DOWN")
                    .latencyMs(latencyMs)
                    .lastChecked(LocalDateTime.now())
                    .errorReason(e.getMessage())
                    .build();
        }
    }

    private Void pingSqlServer() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return null;
    }

    private Void pingMongoDb() {
        mongoTemplate.executeCommand("{ ping: 1 }");
        return null;
    }

    private Void pingNeo4j() {
        neo4jClient.query("RETURN 1").fetch().one();
        return null;
    }
}
