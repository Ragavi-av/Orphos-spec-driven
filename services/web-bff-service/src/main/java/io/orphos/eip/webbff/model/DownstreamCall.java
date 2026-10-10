package io.orphos.eip.webbff.model;

/**
 * A single downstream GET in a screen's fan-out, named by the key it lands on in the
 * aggregate. Declaring screens as lists of these keeps the controller free of HTTP
 * mechanics and makes each screen's true cost — its call count — visible at a glance.
 *
 * @param name    aggregate key, e.g. {@code "plugins"}
 * @param baseUrl downstream origin, from {@link io.orphos.eip.webbff.config.WebBffProperties}
 * @param path    request path including any query string
 */
public record DownstreamCall(String name, String baseUrl, String path) {
}
