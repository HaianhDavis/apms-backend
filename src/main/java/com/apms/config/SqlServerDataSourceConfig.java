package com.apms.config;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SqlServerDataSourceConfig {

    private static final Pattern DATABASE_NAME_PATTERN =
        Pattern.compile("(?i)(^|;)databaseName=([^;]+)");
    private static final Pattern SAFE_DATABASE_PATTERN =
        Pattern.compile("[A-Za-z0-9_]+");

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        ensureDatabaseExists(properties);
        return properties.initializeDataSourceBuilder()
            .type(HikariDataSource.class)
            .build();
    }

    private void ensureDatabaseExists(DataSourceProperties properties) {
        String url = properties.getUrl();
        if (url == null || !url.startsWith("jdbc:sqlserver:")) {
            return;
        }

        String databaseName = extractDatabaseName(url);
        if (databaseName == null || !SAFE_DATABASE_PATTERN.matcher(databaseName).matches()) {
            return;
        }

        String adminUrl = replaceDatabaseName(url, databaseName, "master");

        try (Connection connection = DriverManager.getConnection(
                adminUrl,
                properties.getUsername(),
                properties.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(
                "IF DB_ID(N'" + databaseName + "') IS NULL CREATE DATABASE [" + databaseName + "]");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Failed to initialize SQL Server database '" + databaseName + "'", exception);
        }
    }

    private String extractDatabaseName(String url) {
        Matcher matcher = DATABASE_NAME_PATTERN.matcher(url);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(2);
    }

    private String replaceDatabaseName(String url, String currentDatabaseName, String targetDatabaseName) {
        return url.replaceFirst(
            "(?i)(^|;)databaseName=" + Pattern.quote(currentDatabaseName),
            "$1databaseName=" + targetDatabaseName);
    }
}
