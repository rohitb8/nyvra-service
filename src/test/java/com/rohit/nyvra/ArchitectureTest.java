package com.rohit.nyvra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Skeleton architecture check: the Spring Modulith model builds from the main class.
 *
 * <p>TODO: tighten to {@code modules.verify()} once module boundaries and named interfaces are
 * defined (see {@code design-docs/} — a future {@code ARCHITECTURE.md} / {@code TEST_STRATEGY.md}).
 * Kept lenient here so the skeleton has a fast, infra-free test that passes.
 */
class ArchitectureTest {

    @Test
    void modularityModelBuilds() {
        ApplicationModules modules = ApplicationModules.of(NyvraApplication.class);
        assertThat(modules).isNotNull();
        modules.forEach(module -> assertThat(module.getName()).isNotBlank());
    }
}
