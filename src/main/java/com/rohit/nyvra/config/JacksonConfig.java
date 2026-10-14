package com.rohit.nyvra.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON defaults: ISO-8601 dates (no timestamps), omit nulls.
 */
@Configuration
public class JacksonConfig {

    /**
     * Writes dates as ISO-8601 strings and leaves null properties out.
     *
     * @return the customizer applied to the shared {@code ObjectMapper} builder
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
