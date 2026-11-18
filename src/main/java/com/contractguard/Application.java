package com.contractguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Contract Guard — API contract registry and breaking-change detection platform.
 *
 * Modular monolith: pure-Java core packages (specs, compatibility, consumers, impact,
 * policy, domain) hold no Spring annotations and no I/O; Spring lives only at the
 * edges (api, persistence, notify, audit).
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
