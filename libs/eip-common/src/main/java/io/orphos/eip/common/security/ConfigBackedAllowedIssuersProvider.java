package io.orphos.eip.common.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default {@link AllowedIssuersProvider} that snapshots issuer URLs from
 * {@link SecurityProperties}. Used by every service except platform-core-service,
 * which overrides the bean with a DB-backed provider.
 *
 * <p>The returned set always includes {@link SecurityProperties#getPlatformIssuer()}
 * when set, so platform-superadmin tokens are accepted alongside tenant tokens.</p>
 */
public class ConfigBackedAllowedIssuersProvider implements AllowedIssuersProvider {

    private final SecurityProperties props;

    public ConfigBackedAllowedIssuersProvider(SecurityProperties props) {
        this.props = props;
    }

    @Override
    public Set<String> allowedIssuers() {
        Set<String> set = new LinkedHashSet<>();
        if (props.getAllowedIssuers() != null) {
            for (String iss : props.getAllowedIssuers()) {
                if (iss != null && !iss.isBlank()) set.add(iss.trim());
            }
        }
        String platform = props.getPlatformIssuer();
        if (platform != null && !platform.isBlank()) set.add(platform.trim());
        return set;
    }
}
