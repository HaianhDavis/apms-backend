package com.apms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApmsBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApmsBackendApplication.class, args);
    }

}
