package com.rohit.nyvra.analytics;

import java.util.List;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads {@link FinancialRules} straight from {@code application.yml}, so unit tests exercise the real
 * shipped parameters without a Spring context and without repeating any number in Java.
 */
final class FinancialRulesFixture {

    /** Not instantiable. */
    private FinancialRulesFixture() {
    }

    /**
     * Binds the {@code nyvra.financial-rules} section of {@code application.yml}.
     *
     * @return the shipped rules
     */
    static FinancialRules load() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources))
                .bind("nyvra.financial-rules", FinancialRules.class)
                .get();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read application.yml", e);
        }
    }

    /**
     * Binds the {@code nyvra.health-score-inputs} section of {@code application.yml}.
     *
     * @return the shipped window and gate parameters
     */
    static HealthScoreInputRules loadInputs() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
            return new Binder(ConfigurationPropertySources.from(sources))
                .bind("nyvra.health-score-inputs", HealthScoreInputRules.class)
                .get();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read application.yml", e);
        }
    }
}
