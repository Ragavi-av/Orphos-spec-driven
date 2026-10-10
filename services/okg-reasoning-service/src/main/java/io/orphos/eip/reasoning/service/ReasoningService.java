package io.orphos.eip.reasoning.service;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.reasoning.repository.FactLoader;
import io.orphos.eip.reasoning.repository.FusekiWriter;
import io.orphos.eip.reasoning.component.OntologyService;
import io.orphos.eip.reasoning.service.TenantInfoCache;
import io.orphos.eip.reasoning.vo.TenantRoutingInfo;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Runs one tenant's reasoning cycle:
 * <ol>
 *   <li>Build a fresh {@link OntModel} seeded with the base ontology schema.</li>
 *   <li>Load the tenant's facts from ArcadeDB into it ({@link FactLoader}).</li>
 *   <li>Read back the reasoner's inferred closure for the one genuinely
 *       transitive relationship in the schema ({@code blocks}/{@code blockedBy})
 *       via {@link OntModel#getDeductionsModel()} — this is real OWL inference,
 *       not hand-written traversal code.</li>
 *   <li>Compute analytical facts that OWL/RDFS cannot express natively (date
 *       comparison, counting) in plain Java: {@code OverdueIssue},
 *       {@code BlockedIssue}, {@code OverloadedPerson} — see each private
 *       method's Javadoc for exactly what it checks.</li>
 *   <li>Write the tenant's facts + inferred relationships + computed classes
 *       (deliberately excluding the schema itself, which every tenant would
 *       otherwise redundantly repeat) to Fuseki via {@link FusekiWriter}.</li>
 * </ol>
 *
 * <p>Must be called with a tenant already bound via {@link TenantContextHolder}.</p>
 */
@Service
public class ReasoningService {

    private static final Logger log = LogManager.getLogger(ReasoningService.class);
    private static final String NS = OntologyService.NS;

    private final OntologyService ontology;
    private final FactLoader factLoader;
    private final FusekiWriter fusekiWriter;
    private final TenantInfoCache tenantInfo;
    private final io.orphos.eip.core.arcadedb.ArcadeConnectionResolver resolver;
    private final int overloadThreshold;

    public ReasoningService(OntologyService ontology, FactLoader factLoader, FusekiWriter fusekiWriter,
                             TenantInfoCache tenantInfo,
                             io.orphos.eip.core.arcadedb.ArcadeConnectionResolver resolver,
                             @Value("${reasoning.overload-threshold:5}") int overloadThreshold) {
        this.ontology = ontology;
        this.factLoader = factLoader;
        this.fusekiWriter = fusekiWriter;
        this.tenantInfo = tenantInfo;
        this.resolver = resolver;
        this.overloadThreshold = overloadThreshold;
    }

    public record CycleResult(int factCount, int inferredBlockCount, int overdueCount,
                               int blockedCount, int overloadedCount) {}

    public CycleResult reasonForCurrentTenant() {
        UUID tenantId = TenantContextHolder.requireTenantId();

        TenantRoutingInfo routing = tenantInfo.find(tenantId)
                .filter(TenantRoutingInfo::active)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot resolve routing (or tenant inactive) for " + tenantId + " — skipping reasoning"));

        OntModel model = ontology.newReasoningModel();
        factLoader.loadFacts(model, resolver.templateFor(routing.arcadeConnectionId()), routing.dataset(), tenantId);

        if (model.difference(ontology.baseSchemaModel()).isEmpty()) {
            log.debug("Tenant {} has no ingested OKG facts yet — skipping reasoning cycle", tenantId);
            return new CycleResult(0, 0, 0, 0, 0);
        }

        // Genuine OWL inference: the reasoner's derived closure, filtered to
        // the one transitive relationship this ontology declares. Excludes
        // RDFS/OWL meta-level noise (domain/range restatement, subClassOf
        // reflexivity, etc.) that OWL_MEM_RULE_INF also produces but which
        // isn't useful signal for downstream consumers.
        Model deductions = model.getDeductionsModel();
        Property blocks = model.getProperty(NS + "blocks");
        Property blockedBy = model.getProperty(NS + "blockedBy");
        Model inferredBlocking = ModelFactory.createDefaultModel();
        inferredBlocking.add(deductions.listStatements(null, blocks, (RDFNode) null));
        inferredBlocking.add(deductions.listStatements(null, blockedBy, (RDFNode) null));

        // Output = tenant facts (schema excluded) + the inferred blocking closure.
        Model output = ModelFactory.createDefaultModel();
        output.add(model.difference(ontology.baseSchemaModel()));
        output.add(inferredBlocking);

        int overdueCount = assertOverdueIssues(model, output);
        int blockedCount = assertBlockedIssues(model, inferredBlocking, output);
        int overloadedCount = assertOverloadedPeople(model, output);

        fusekiWriter.write(routing, output);

        CycleResult result = new CycleResult((int) model.difference(ontology.baseSchemaModel()).size(),
                (int) inferredBlocking.size(), overdueCount, blockedCount, overloadedCount);
        log.info("Reasoning cycle complete for tenant {}: {}", tenantId, result);
        return result;
    }

    /** Overdue: {@code dueDate} is a parseable past instant AND statusCategory != "Done". */
    private int assertOverdueIssues(OntModel model, Model output) {
        Property dueDateProp = model.getProperty(NS + "dueDate");
        Property statusCategoryProp = model.getProperty(NS + "statusCategory");
        Property rdfType = model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Resource overdueClass = output.createResource(NS + "OverdueIssue");
        Instant now = Instant.now();

        int count = 0;
        StmtIterator it = model.listStatements(null, dueDateProp, (RDFNode) null);
        while (it.hasNext()) {
            Statement s = it.next();
            Resource issue = s.getSubject();
            String due = s.getString();
            String statusCategory = firstString(model, issue, statusCategoryProp);
            if (isPastDate(due, now) && !"Done".equalsIgnoreCase(statusCategory)) {
                output.add(issue, rdfType, overdueClass);
                count++;
            }
        }
        return count;
    }

    /**
     * Blocked: has at least one blocker (via the OWL-inferred transitive
     * {@code blockedBy} closure, falling back to the directly-asserted edges
     * if the reasoner produced none) whose statusCategory != "Done".
     */
    private int assertBlockedIssues(OntModel model, Model inferredBlocking, Model output) {
        Property blockedByProp = model.getProperty(NS + "blockedBy");
        Property statusCategoryProp = model.getProperty(NS + "statusCategory");
        Property rdfType = model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Resource blockedClass = output.createResource(NS + "BlockedIssue");

        // Union asserted + inferred blockedBy edges so this works even when
        // the reasoner's deductions model happens to only materialise one
        // direction of the blocks/blockedBy inverse pair.
        Model allBlockedBy = ModelFactory.createDefaultModel();
        allBlockedBy.add(model.listStatements(null, blockedByProp, (RDFNode) null));
        allBlockedBy.add(inferredBlocking.listStatements(null, blockedByProp, (RDFNode) null));

        Set<Resource> blocked = new LinkedHashSet<>();
        StmtIterator it = allBlockedBy.listStatements();
        while (it.hasNext()) {
            Statement s = it.next();
            Resource issue = s.getSubject();
            if (blocked.contains(issue)) continue;
            Resource blocker = s.getObject().asResource();
            String blockerStatus = firstString(model, blocker, statusCategoryProp);
            if (!"Done".equalsIgnoreCase(blockerStatus)) {
                blocked.add(issue);
            }
        }
        for (Resource issue : blocked) {
            output.add(issue, rdfType, blockedClass);
        }
        return blocked.size();
    }

    /** Overloaded: more than {@code reasoning.overload-threshold} issues assigned with statusCategory != "Done". */
    private int assertOverloadedPeople(OntModel model, Model output) {
        Property assignedTo = model.getProperty(NS + "assignedTo");
        Property statusCategoryProp = model.getProperty(NS + "statusCategory");
        Property rdfType = model.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        Resource overloadedClass = output.createResource(NS + "OverloadedPerson");

        Map<Resource, Integer> openCountByPerson = new LinkedHashMap<>();
        StmtIterator it = model.listStatements(null, assignedTo, (RDFNode) null);
        while (it.hasNext()) {
            Statement s = it.next();
            Resource issue = s.getSubject();
            Resource person = s.getObject().asResource();
            String statusCategory = firstString(model, issue, statusCategoryProp);
            if (!"Done".equalsIgnoreCase(statusCategory)) {
                openCountByPerson.merge(person, 1, Integer::sum);
            }
        }
        int overloaded = 0;
        for (Map.Entry<Resource, Integer> e : openCountByPerson.entrySet()) {
            if (e.getValue() > overloadThreshold) {
                output.add(e.getKey(), rdfType, overloadedClass);
                overloaded++;
            }
        }
        return overloaded;
    }

    private static String firstString(Model model, Resource subject, Property property) {
        StmtIterator it = model.listStatements(subject, property, (RDFNode) null);
        return it.hasNext() ? it.next().getString() : null;
    }

    private static boolean isPastDate(String isoDateOrDateTime, Instant now) {
        if (isoDateOrDateTime == null || isoDateOrDateTime.isBlank()) return false;
        try {
            if (isoDateOrDateTime.length() <= 10) {
                return java.time.LocalDate.parse(isoDateOrDateTime).isBefore(now.atZone(java.time.ZoneOffset.UTC).toLocalDate());
            }
            return Instant.parse(isoDateOrDateTime).isBefore(now);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
