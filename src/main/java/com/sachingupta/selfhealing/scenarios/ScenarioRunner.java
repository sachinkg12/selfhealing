package com.sachingupta.selfhealing.scenarios;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves CLI arguments to scenarios and runs them. {@code --scenario <slug>} runs one, {@code
 * --all} runs every registered scenario.
 */
@Component
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final List<Scenario> scenarios;

    public ScenarioRunner(List<Scenario> scenarios) {
        this.scenarios = List.copyOf(scenarios);
    }

    public void run(String... args) {
        if (args.length == 0) {
            printMenu();
            return;
        }
        if (matches(args, "--all")) {
            runAll();
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--scenario") && i + 1 < args.length) {
                runOne(args[i + 1]);
                return;
            }
        }
        printMenu();
    }

    private void runAll() {
        for (Scenario s : scenarios) {
            banner(s);
            s.run();
        }
    }

    private void runOne(String slug) {
        Scenario s =
                scenarios.stream()
                        .filter(x -> x.slug().equals(slug))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown scenario: "
                                                        + slug
                                                        + ". Known: "
                                                        + scenarios.stream()
                                                                .map(Scenario::slug)
                                                                .toList()));
        banner(s);
        s.run();
    }

    private void banner(Scenario s) {
        log.info("");
        log.info("====================================================================");
        log.info("Scenario: {} — {}", s.slug(), s.title());
        log.info("Claim: {}", s.claim());
        log.info("====================================================================");
    }

    private void printMenu() {
        log.info("");
        log.info("Usage:");
        log.info("  ./mvnw spring-boot:run -Dspring-boot.run.arguments=\"--scenario <slug>\"");
        log.info("  ./mvnw spring-boot:run -Dspring-boot.run.arguments=\"--all\"");
        log.info("");
        log.info("Available scenarios:");
        for (Scenario s : scenarios) {
            log.info("  {}  --  {}", String.format("%-22s", s.slug()), s.title());
        }
    }

    private static boolean matches(String[] args, String token) {
        for (String a : args) {
            if (a.equals(token)) {
                return true;
            }
        }
        return false;
    }
}
