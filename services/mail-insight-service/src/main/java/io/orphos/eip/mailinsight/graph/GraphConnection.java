package io.orphos.eip.mailinsight.graph;

/**
 * Resolved Microsoft Graph connection for one tenant: the Azure AD directory,
 * the registered app's client id, the (Infisical-resolved) client secret, and the
 * Graph base URL. Built by the pipeline from MailMonitorConfig + Infisical.
 */
public record GraphConnection(
        String directoryId,
        String clientId,
        String clientSecret,
        String graphBaseUrl
) {
    public boolean isComplete() {
        return notBlank(directoryId) && notBlank(clientId) && notBlank(clientSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
