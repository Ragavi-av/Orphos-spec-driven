package io.orphos.eip.reasoning.component;

import jakarta.annotation.PostConstruct;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the base schema once at startup into a Jena RDF model — the UNION of
 * every configured ontology file. Each reasoning cycle then builds a fresh
 * {@code OntModel} (with rule inference enabled) over this base schema plus one
 * tenant's facts — see {@link io.orphos.eip.reasoning.service.ReasoningService}.
 *
 * <p>Each ontology uses its own namespace ({@code …/core#}, {@code …/enterprise#},
 * {@code …/governance#}, {@code …/finance#}, and so on) so they load side by side
 * without term collisions, and each contributes its own inferences into the same
 * reasoner — {@code ent:reportsTo} transitivity, {@code gov:inheritsFrom}
 * transitivity, the {@code owl:hasValue} classification restrictions in the
 * domain files, and the cross-file alignment axioms that tie tenant-local terms
 * (e.g. {@code zen:CashFlowMonth}) to the shared model.</p>
 *
 * <h2>Why the paths are an explicit list</h2>
 * RDF/XML parsing into a plain {@code Model} does <b>not</b> follow
 * {@code owl:imports} — Jena only processes imports through
 * {@code OntDocumentManager}, which this service deliberately does not use for
 * the base schema. So an umbrella file with imports would silently load nothing
 * but itself, and the union has to be stated. {@code reasoning.ontology-paths}
 * is that statement.
 *
 * <p>Configuration precedence, chosen so no existing deployment changes
 * behaviour on upgrade:</p>
 * <ol>
 *   <li>{@code reasoning.ontology-paths} — a comma-separated list. If set, it is
 *       the whole schema and the two legacy properties are ignored.</li>
 *   <li>Otherwise the original pair, {@code reasoning.ontology-path} +
 *       {@code reasoning.enterprise-ontology-path}, exactly as before.</li>
 * </ol>
 * Every listed resource is REQUIRED: a typo that silently dropped a domain
 * ontology would silently drop the classification axioms that RBAC depends on,
 * so a missing file fails startup rather than degrading quietly.
 *
 * <p>The schema itself never changes at runtime, so it's safe to share the
 * base model read-only across tenants and cycles; only the per-cycle overlay
 * (facts + reasoner) is tenant-specific and short-lived.</p>
 */
@Component
public class OntologyService {

    private static final Logger log = LogManager.getLogger(OntologyService.class);
    public static final String NS = "http://orphos.eip/ontology/core#";
    public static final String ENTERPRISE_NS = "http://orphos.eip/ontology/enterprise#";

    private final ResourceLoader resourceLoader;
    private final String ontologyPaths;
    private final String ontologyPath;
    private final String enterpriseOntologyPath;
    private org.apache.jena.rdf.model.Model baseSchema;

    public OntologyService(ResourceLoader resourceLoader,
                            @Value("${reasoning.ontology-paths:}") String ontologyPaths,
                            @Value("${reasoning.ontology-path:classpath:eip-core.owl}") String ontologyPath,
                            @Value("${reasoning.enterprise-ontology-path:classpath:eip-enterprise.owl}")
                                    String enterpriseOntologyPath) {
        this.resourceLoader = resourceLoader;
        this.ontologyPaths = ontologyPaths;
        this.ontologyPath = ontologyPath;
        this.enterpriseOntologyPath = enterpriseOntologyPath;
    }

    @PostConstruct
    void load() {
        org.apache.jena.rdf.model.Model model = ModelFactory.createDefaultModel();
        List<String> paths = resolvePaths();
        for (String path : paths) {
            long before = model.size();
            // Base URI is left null: every ontology in this family declares its own
            // xml:base, which takes precedence in RDF/XML anyway, and passing one
            // file's namespace while reading another is how relative references end
            // up resolved against the wrong ontology.
            readInto(model, path, null);
            log.debug("  {} → {} statements", path, model.size() - before);
        }
        this.baseSchema = model;
        log.info("Loaded base schema — {} statements from {} ontologies: {}",
                model.size(), paths.size(), paths);
    }

    /**
     * The ontology files to union, honouring the precedence documented on the
     * class. Blank entries are dropped so a trailing comma in configuration is
     * harmless.
     */
    private List<String> resolvePaths() {
        if (ontologyPaths != null && !ontologyPaths.isBlank()) {
            List<String> out = new ArrayList<>();
            for (String raw : ontologyPaths.split(",")) {
                String p = raw.strip();
                if (!p.isEmpty()) out.add(p);
            }
            if (!out.isEmpty()) return out;
        }
        List<String> legacy = new ArrayList<>();
        legacy.add(ontologyPath);
        if (enterpriseOntologyPath != null && !enterpriseOntologyPath.isBlank()) {
            legacy.add(enterpriseOntologyPath);
        }
        return legacy;
    }

    private void readInto(org.apache.jena.rdf.model.Model model, String path, String baseNs) {
        Resource resource = resourceLoader.getResource(path);
        try (InputStream in = resource.getInputStream()) {
            model.read(in, baseNs, "RDF/XML");
        } catch (Exception e) {
            throw new IllegalStateException("Could not load ontology at " + path, e);
        }
    }

    /**
     * Builds a fresh reasoning-enabled {@link OntModel} seeded with the base
     * schema — callers add tenant facts into the returned model, then read
     * back the reasoner's inferred closure via its statement iterator.
     */
    public OntModel newReasoningModel() {
        org.apache.jena.rdf.model.Model facts = ModelFactory.createDefaultModel();
        facts.add(baseSchema);
        return ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_RULE_INF, facts);
    }

    /**
     * The schema-only model (no tenant facts) — used by callers to compute
     * {@code reasoningModel.difference(baseSchemaModel())} and isolate just
     * the tenant-asserted facts from a populated reasoning model.
     */
    public org.apache.jena.rdf.model.Model baseSchemaModel() {
        return baseSchema;
    }
}
