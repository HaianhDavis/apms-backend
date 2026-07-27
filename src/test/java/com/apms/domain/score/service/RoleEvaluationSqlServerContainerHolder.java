package com.apms.domain.score.service;

import org.testcontainers.containers.MSSQLServerContainer;

public class RoleEvaluationSqlServerContainerHolder {
    public static final MSSQLServerContainer<?> SQL_CONTAINER = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense();

    static {
        SQL_CONTAINER.start();
    }
}
