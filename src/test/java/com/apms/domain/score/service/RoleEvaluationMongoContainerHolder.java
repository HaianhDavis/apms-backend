package com.apms.domain.score.service;

import org.testcontainers.containers.MongoDBContainer;

public class RoleEvaluationMongoContainerHolder {
    public static final MongoDBContainer MONGO_CONTAINER = new MongoDBContainer("mongo:7.0")
            .withCommand("--replSet", "rs0");

    static {
        MONGO_CONTAINER.start();
        try {
            org.testcontainers.containers.Container.ExecResult result = MONGO_CONTAINER.execInContainer(
                "mongosh", "--quiet", "--eval",
                "try { rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]}) } catch(e) { if(e.codeName !== 'AlreadyInitialized') throw e; }"
            );
            if (result.getExitCode() != 0) {
                throw new RuntimeException("Failed to initialize MongoDB replica set: " + result.getStderr());
            }

            boolean isPrimary = false;
            for (int i = 0; i < 30; i++) {
                org.testcontainers.containers.Container.ExecResult statusResult = MONGO_CONTAINER.execInContainer(
                    "mongosh", "--quiet", "--eval", "rs.isMaster().ismaster"
                );
                if (statusResult.getStdout().trim().equals("true")) {
                    isPrimary = true;
                    break;
                }
                Thread.sleep(1000);
            }
            if (!isPrimary) {
                throw new RuntimeException("MongoDB node did not become PRIMARY in time.");
            }
        } catch (Exception e) {
            throw new RuntimeException("MongoDB replica set initialization failed", e);
        }
    }
}
