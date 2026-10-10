package io.orphos.eip.ingest.vo;

import io.orphos.eip.ingest.repository.OkgConnectionRepository;

/**
 * Resolved Jira credentials for one tenant, held only for the duration of a
 * sync call — never logged, never persisted (the persisted form is
 * {@code OkgConnectionRepository}'s row, which stores {@code apiTokenRef}
 * instead of the raw token).
 */
public record JiraConnection(String baseUrl, String email, String apiToken, String projectKeys) {

    public String[] projectKeyList() {
        if (projectKeys == null || projectKeys.isBlank()) return new String[0];
        return java.util.Arrays.stream(projectKeys.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
