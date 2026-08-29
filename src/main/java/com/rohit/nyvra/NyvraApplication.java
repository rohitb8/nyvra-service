package com.rohit.nyvra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * nyvra — personal finance accountant.
 *
 * <p>Modular monolith: each top-level package under {@code com.rohit.nyvra} that is not a shared
 * technical package ({@code config}, {@code common}) is a Spring Modulith module. See
 * {@code design-docs/DOMAIN_MODEL.md} for the bounded contexts.
 */
@Modulithic(systemName = "nyvra")
@SpringBootApplication
public class NyvraApplication {

    public static void main(String[] args) {
        SpringApplication.run(NyvraApplication.class, args);
    }
}
