package io.orphos.eip.reasoning.repository;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.reasoning.component.OntologyService;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Reads the tenant's ingested Jira-derived entities from ArcadeDB (written by
 * okg-ingest-service) and asserts them as RDF facts into a reasoning model.
 *
 * <p>URIs are minted as {@code urn:eip:<type>:<tenantId>:<externalId>} — the
 * tenantId segment keeps them unique even though the reasoning model itself
 * is built fresh per tenant per cycle (belt-and-braces against ever
 * accidentally sharing a model instance across tenants).</p>
 */
@Repository
public class FactLoader {

    // Stateless: the routed ArcadeDbTemplate is passed per call (resolved by the
    // caller from the tenant's connection) so facts are read from the ArcadeDB
    // server that actually hosts the tenant's database.

    public void loadFacts(OntModel model, ArcadeDbTemplate arcade, String targetDb, UUID tenantId) {
        String tenant = tenantId.toString();

        Property rdfType = model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Resource projectClass = model.getResource(OntologyService.NS + "Project");
        Resource issueClass = model.getResource(OntologyService.NS + "Issue");
        Resource personClass = model.getResource(OntologyService.NS + "Person");

        Property externalId = model.getProperty(OntologyService.NS + "externalId");
        Property displayName = model.getProperty(OntologyService.NS + "displayName");
        Property summary = model.getProperty(OntologyService.NS + "summary");
        Property statusName = model.getProperty(OntologyService.NS + "statusName");
        Property statusCategory = model.getProperty(OntologyService.NS + "statusCategory");
        Property issueTypeName = model.getProperty(OntologyService.NS + "issueTypeName");
        Property priorityName = model.getProperty(OntologyService.NS + "priorityName");
        Property dueDate = model.getProperty(OntologyService.NS + "dueDate");
        Property assignedTo = model.getProperty(OntologyService.NS + "assignedTo");
        Property reportedBy = model.getProperty(OntologyService.NS + "reportedBy");
        Property belongsToProject = model.getProperty(OntologyService.NS + "belongsToProject");
        Property blocks = model.getProperty(OntologyService.NS + "blocks");
        Property blockedBy = model.getProperty(OntologyService.NS + "blockedBy");
        Property relatesTo = model.getProperty(OntologyService.NS + "relatesTo");
        Property duplicates = model.getProperty(OntologyService.NS + "duplicates");

        for (Map<String, Object> row : arcade.queryOn(targetDb,
                "SELECT FROM OkgProject WHERE tenantId = :tenantId", Map.of("tenantId", tenant))) {
            String id = str(row, "externalId");
            if (id == null) continue;
            Resource r = model.createResource(projectUri(tenant, id));
            r.addProperty(rdfType, projectClass);
            r.addProperty(externalId, id);
            r.addProperty(displayName, str(row, "name", ""));
        }

        for (Map<String, Object> row : arcade.queryOn(targetDb,
                "SELECT FROM OkgPerson WHERE tenantId = :tenantId", Map.of("tenantId", tenant))) {
            String id = str(row, "externalId");
            if (id == null) continue;
            Resource r = model.createResource(personUri(tenant, id));
            r.addProperty(rdfType, personClass);
            r.addProperty(externalId, id);
            r.addProperty(displayName, str(row, "displayName", ""));
        }

        for (Map<String, Object> row : arcade.queryOn(targetDb,
                "SELECT FROM OkgIssue WHERE tenantId = :tenantId", Map.of("tenantId", tenant))) {
            String id = str(row, "externalId");
            if (id == null) continue;
            Resource r = model.createResource(issueUri(tenant, id));
            r.addProperty(rdfType, issueClass);
            r.addProperty(externalId, id);
            r.addProperty(summary, str(row, "summary", ""));
            r.addProperty(statusName, str(row, "statusName", ""));
            r.addProperty(statusCategory, str(row, "statusCategory", ""));
            r.addProperty(issueTypeName, str(row, "issueTypeName", ""));
            String priority = str(row, "priorityName");
            if (priority != null && !priority.isBlank()) r.addProperty(priorityName, priority);
            String due = str(row, "dueDate");
            if (due != null && !due.isBlank()) r.addProperty(dueDate, due);

            String assignee = str(row, "assigneeExternalId");
            if (assignee != null && !assignee.isBlank()) {
                r.addProperty(assignedTo, model.createResource(personUri(tenant, assignee)));
            }
            String reporter = str(row, "reporterExternalId");
            if (reporter != null && !reporter.isBlank()) {
                r.addProperty(reportedBy, model.createResource(personUri(tenant, reporter)));
            }
            String projectExternalId = str(row, "projectExternalId");
            if (projectExternalId != null && !projectExternalId.isBlank()) {
                r.addProperty(belongsToProject, model.createResource(projectUri(tenant, projectExternalId)));
            }
        }

        for (Map<String, Object> row : arcade.queryOn(targetDb,
                "SELECT FROM OkgIssueLink WHERE tenantId = :tenantId", Map.of("tenantId", tenant))) {
            String from = str(row, "fromExternalId");
            String to = str(row, "toExternalId");
            String type = str(row, "linkType", "relatesTo");
            if (from == null || to == null) continue;
            Resource fromRes = model.createResource(issueUri(tenant, from));
            Resource toRes = model.createResource(issueUri(tenant, to));
            switch (type) {
                case "blocks" -> fromRes.addProperty(blocks, toRes);
                case "blockedBy" -> fromRes.addProperty(blockedBy, toRes);
                case "duplicates" -> fromRes.addProperty(duplicates, toRes);
                default -> fromRes.addProperty(relatesTo, toRes);
            }
        }
    }

    public static String projectUri(String tenantId, String externalId) {
        return "urn:eip:project:" + tenantId + ":" + externalId;
    }

    public static String personUri(String tenantId, String externalId) {
        return "urn:eip:person:" + tenantId + ":" + externalId;
    }

    public static String issueUri(String tenantId, String externalId) {
        return "urn:eip:issue:" + tenantId + ":" + externalId;
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? null : String.valueOf(v);
    }

    private static String str(Map<String, Object> row, String field, String fallback) {
        String v = str(row, field);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
