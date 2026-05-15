package com.sachingupta.selfhealing;

import com.sachingupta.selfhealing.scenarios.ScenarioRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class SelfHealingApplication {

    public static void main(String[] args) {
        // Closing the context triggers @PreDestroy on every bean, including
        // SlackApprovalGate which shuts down its Socket Mode worker pool.
        // Without this, Bolt's non-daemon threads keep the JVM alive after
        // the scenario finishes.
        SpringApplication.run(SelfHealingApplication.class, args).close();
    }

    @Bean
    CommandLineRunner cli(ScenarioRunner runner) {
        return runner::run;
    }
}
