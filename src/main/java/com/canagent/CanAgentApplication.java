package com.canagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CanAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CanAgentApplication.class, args);
    }
}
