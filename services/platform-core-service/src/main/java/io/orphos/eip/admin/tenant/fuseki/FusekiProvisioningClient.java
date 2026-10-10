package io.orphos.eip.admin.tenant.fuseki;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * Provisions a per-tenant Fuseki dataset for DEDICATED-mode tenants.
 *
 * <p>Fuseki's admin API:
 * <ul>
 *   <li>{@code GET /$/datasets/{name}}  — existence probe (200 = exists)</li>
 *   <li>{@code POST /$/datasets} with form body {@code dbName=NAME&dbType=tdb2}
 *       — creates a new persistent TDB2-backed dataset</li>
 *   <li>{@code DELETE /$/datasets/{name}} — drops the dataset (used on
 *       tenant hard-delete; soft-delete intentionally keeps it).</li>
 * </ul>
 *
 * <p>Mirrors the shape of {@link io.orphos.eip.admin.tenant.arcade.ArcadeProvisioningClient}
 * and {@link io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient}:
 * idempotent semantics, never throws on a benign "already exists" path.</p>
 *
 * <p>Naming convention: the Fuseki dataset name MUST match the tenant's
 * {@code arcadeDatabase} field (e.g. {@code eip_tenant_{uuid}}). Reusing the
 * same string for both stores keeps {@code TenantService.create()} idempotent
 * and lets graph-proxy compute the dataset name from a single field.</p>
 */
@Component
public class FusekiProvisioningClient {

    private static final Logger log = LogManager.getLogger(FusekiProvisioningClient.class);

    @Autowired
    private RestTemplate rest;

    @Value("${fuseki.url}")
    private String fusekiUrl;

    @Value("${fuseki.user}")
    private String fusekiUser;

    @Value("${fuseki.password}")
    private String fusekiPassword;

    /**
     * Idempotently creates a Fuseki dataset with the given name. Returns
     * {@code true} if a new dataset was created, {@code false} if it already
     * existed.
     */
    public boolean ensureDataset(String datasetName) {
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException("datasetName must not be blank");
        }
        if (datasetExists(datasetName)) {
            log.info("Fuseki dataset '{}' already exists — skipping creation", datasetName);
            return false;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(fusekiUser, fusekiPassword);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("dbName", datasetName);
        // tdb2 = persistent on-disk store. Use "mem" for in-memory if a test
        // dataset ever needs to be ephemeral; not appropriate for tenant data.
        form.add("dbType", "tdb2");

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(form, headers);
        rest.exchange(fusekiUrl + "/$/datasets", HttpMethod.POST, entity, String.class);
        log.info("Provisioned Fuseki dataset '{}'", datasetName);
        return true;
    }

    /**
     * Drops a Fuseki dataset. Used for tenant hard-delete only. Best-effort:
     * a missing dataset logs and returns rather than failing.
     */
    public void dropDataset(String datasetName) {
        if (datasetName == null || datasetName.isBlank()) return;
        if (!datasetExists(datasetName)) {
            log.warn("Fuseki dataset '{}' did not exist at drop time — nothing to do", datasetName);
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(fusekiUser, fusekiPassword);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        rest.exchange(fusekiUrl + "/$/datasets/" + datasetName, HttpMethod.DELETE, entity, String.class);
        log.info("Dropped Fuseki dataset '{}'", datasetName);
    }

    /**
     * Runs a SPARQL UPDATE against the data endpoint of a Fuseki dataset.
     * Used by {@code DemoTenantBootstrap} to migrate the existing orphos
     * default-graph data into the demo tenant's named graph so SHARED-mode
     * SPARQL queries (which scope to {@code urn:eip:tenant:{uuid}}) actually
     * return data. Could also be used by future data-migration tooling.
     *
     * <p>Caller is responsible for idempotency — many SPARQL Update statements
     * are naturally idempotent (e.g. {@code ADD DEFAULT TO <named>} dedupes
     * triples in the destination set), but some are not (e.g. {@code MOVE}).</p>
     */
    public void executeUpdate(String datasetName, String sparqlUpdate) {
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException("datasetName must not be blank");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("application/sparql-update"));
        headers.setBasicAuth(fusekiUser, fusekiPassword);
        HttpEntity<String> entity = new HttpEntity<>(sparqlUpdate, headers);
        rest.exchange(fusekiUrl + "/" + datasetName + "/update",
                HttpMethod.POST, entity, String.class);
        if (log.isDebugEnabled()) {
            log.debug("Fuseki UPDATE on '{}' executed ({} chars)", datasetName, sparqlUpdate.length());
        }
    }

    /**
     * Uploads an OWL/RDF-XML document into a dataset's default graph via the
     * Graph Store Protocol ({@code POST /{dataset}/data}). Used by data
     * onboarding to publish the auto-generated ontology. Best-effort: logs and
     * swallows failures so a Fuseki hiccup never blocks the ArcadeDB load.
     */
    public boolean uploadRdfXml(String datasetName, String rdfXml) {
        if (datasetName == null || datasetName.isBlank() || rdfXml == null || rdfXml.isBlank()) {
            return false;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.valueOf("application/rdf+xml"));
            headers.setBasicAuth(fusekiUser, fusekiPassword);
            HttpEntity<String> entity = new HttpEntity<>(rdfXml, headers);
            rest.exchange(fusekiUrl + "/" + datasetName + "/data", HttpMethod.POST, entity, String.class);
            log.info("Uploaded ontology ({} chars) to Fuseki dataset '{}'", rdfXml.length(), datasetName);
            return true;
        } catch (Exception e) {
            log.warn("Ontology upload to Fuseki dataset '{}' failed: {}", datasetName, e.getMessage());
            return false;
        }
    }

    private boolean datasetExists(String datasetName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(fusekiUser, fusekiPassword);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            rest.exchange(fusekiUrl + "/$/datasets/" + datasetName,
                    HttpMethod.GET, entity, String.class);
            return true;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) return false;
            log.warn("Could not probe Fuseki dataset '{}' existence: {}", datasetName, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Could not probe Fuseki dataset '{}' existence: {}", datasetName, e.getMessage());
            return false;
        }
    }
}
