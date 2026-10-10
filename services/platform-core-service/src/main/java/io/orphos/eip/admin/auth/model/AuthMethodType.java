package io.orphos.eip.admin.auth.model;

/** Auth provider categories. Stored as the {@code type} property on IdentityProvider docs. */
public enum AuthMethodType {
    LOCAL,   // self-managed username/password
    OIDC,    // OpenID Connect (incl. self-registration)
    SAML,    // SAML 2.0
    LDAP     // direct LDAP / AD bind
}
