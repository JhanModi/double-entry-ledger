package io.github.jhanmodi.ledger.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Strict request parsing (ADR-0021). By default, JSON parsing quietly turned {@code "amount": 10.5} into 10 and
 * accepted a transfer for an amount the client never sent; it also ignored fields this API doesn't define.
 * TransfersApiIT reproduced both before this configuration existed.
 *
 * <ul>
 *   <li>A whole-number field (an amount in minor units) accepts only a JSON integer: never a fraction ({@code 10.5},
 *       {@code 1050.0}), exponent notation ({@code 1e3}), a string ({@code "1050"}), or a boolean.
 *   <li>A field the request type doesn't define is an error, not silently dropped.
 * </ul>
 *
 * Either one is a 400 naming the field (ApiExceptionHandler). This only changes how requests are read; responses are
 * written exactly as before.
 */
@Configuration(proxyBeanMethods = false)
class StrictJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictRequestParsing() {
        // Runs after Spring Boot's own customizer, so these settings win over its lenient defaults.
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withCoercionConfig(
                        LogicalType.Integer,
                        integers -> integers.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
