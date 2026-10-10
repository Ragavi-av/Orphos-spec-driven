package io.orphos.eip.common.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Aggregates shared web auto-configurations so services pick them up by
 * adding a single dependency on {@code eip-common}.
 */
@AutoConfiguration
@Import({GlobalExceptionHandler.class, CorsAutoConfiguration.class})
public class CommonWebAutoConfiguration {
}
