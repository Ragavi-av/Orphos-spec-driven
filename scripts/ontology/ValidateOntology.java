import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.rdfxml.xmlinput.ALiteral;
import org.apache.jena.rdfxml.xmlinput.ARP;
import org.apache.jena.rdfxml.xmlinput.AResource;
import org.apache.jena.rdfxml.xmlinput.StatementHandler;
import org.apache.jena.vocabulary.RDF;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the EIP ontology family the way {@code OntologyService} will load
 * it: read every {@code .owl} file in {@code ontology/} into ONE plain Model
 * (RDF/XML parsing does not follow {@code owl:imports}, so the union must be
 * explicit), then run the OWL rule reasoner over it and assert that the
 * inferences the design promises actually fire.
 *
 * <p>This is a regression guard, not a one-off: the persona/RBAC design in
 * eip-governance.owl only works if the transitive closures and the
 * {@code owl:hasValue} classification restrictions really do produce the
 * triples that enforcement will query. Break one of those axioms and a check
 * here fails instead of RBAC quietly opening up.</p>
 *
 * <p>Uses {@link org.apache.jena.reasoner.ReasonerRegistry#getOWLReasoner()} —
 * the same reasoner {@code OntModelSpec.OWL_MEM_RULE_INF} wraps — via
 * {@code createInfModel} rather than {@code createOntologyModel}, because
 * {@code OntDocumentManager} initialisation reaches for RIOT and fails in a
 * sandboxed shell (see {@link #readRdfXml}).</p>
 *
 * <p>Run with the runtime classpath of okg-reasoning-service (which already
 * depends on Jena):</p>
 * <pre>
 *   java -cp "&lt;jena jars&gt;" ValidateOntology.java ../../ontology
 * </pre>
 * Exit code 0 = all checks passed, 1 = at least one failed.
 */
public class ValidateOntology {

    static final String GOV = "http://orphos.eip/ontology/governance#";
    static final String ENT = "http://orphos.eip/ontology/enterprise#";
    static final String FIN = "http://orphos.eip/ontology/finance#";
    static final String HR  = "http://orphos.eip/ontology/hr#";
    static final String EIP = "https://orphos.io/ontology/eip#";
    static final String ZEN = "https://orphos.io/ontology/zenitus#";
    /** The OTHER core: external work items, deployed only in the service's resources. */
    static final String CORE = "http://orphos.eip/ontology/core#";

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        // Accepts any mix of directories and individual .owl files, because the
        // deployed union is NOT simply "everything in ontology/": the work-item core
        // (namespace .../core#) lives only in the reasoning service's resources, and
        // ontology/eip-core.owl is a DIFFERENT ontology (the IT estate, namespace
        // .../eip#) deployed as eip-itops-core.owl. Validating one directory would
        // therefore validate something the service never loads.
        List<String> inputs = args.length > 0 ? List.of(args)
                : List.of("ontology",
                          "services/okg-reasoning-service/src/main/resources/eip-core.owl");

        // ---- Phase 1: parse. Each file separately, so a bad file is named. ----
        Model base = ModelFactory.createDefaultModel();
        List<Path> files = new ArrayList<>();
        for (String in : inputs) {
            Path p = Path.of(in);
            if (Files.isDirectory(p)) {
                try (var s = Files.list(p)) {
                    s.filter(f -> f.toString().endsWith(".owl")).sorted().forEach(files::add);
                }
            } else if (Files.exists(p)) {
                files.add(p);
            } else {
                System.out.printf("  FAIL %-24s does not exist%n", in);
                failures++;
            }
        }
        System.out.println("== Parse ==");
        for (Path f : files) {
            Model m = ModelFactory.createDefaultModel();
            try (InputStream in = Files.newInputStream(f)) {
                readRdfXml(m, in, f.toUri().toString());
                System.out.printf("  OK   %-24s %d triples%n", f.getFileName(), m.size());
                base.add(m);
            } catch (Exception e) {
                System.out.printf("  FAIL %-24s %s%n", f.getFileName(), e.getMessage());
                failures++;
            }
        }
        System.out.printf("  union: %d triples from %d files%n%n", base.size(), files.size());
        if (failures > 0) { System.out.println("Missing inputs - stopping."); System.exit(1); }
        if (failures > 0) { System.out.println("Parse failures - stopping."); System.exit(1); }

        // ---- Phase 2: reason. Same spec OntologyService.newReasoningModel uses. ----
        System.out.println("== Reason (ReasonerRegistry.getOWLReasoner - the reasoner behind OWL_MEM_RULE_INF) ==");
        long t0 = System.currentTimeMillis();
        Model facts = ModelFactory.createDefaultModel();
        facts.add(base);
        InfModel inf = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), facts);
        long size = inf.size();
        System.out.printf("  closure: %d triples (from %d asserted) in %d ms%n%n",
                size, base.size(), System.currentTimeMillis() - t0);

        // ---- Phase 3: the design's claims, as assertions. ----
        System.out.println("== Inference checks ==");

        // 1. Persona inheritance is transitive: CEO must inherit from Viewer and
        //    Guest through Executive, which is asserted nowhere directly.
        checkTriple(inf, GOV + "PersonaCeo", GOV + "inheritsFrom", GOV + "PersonaViewer",
                "ceo inheritsFrom viewer (transitive, 2 hops)");
        checkTriple(inf, GOV + "PersonaCeo", GOV + "inheritsFrom", GOV + "PersonaGuest",
                "ceo inheritsFrom guest (transitive, 3 hops)");
        checkTriple(inf, GOV + "PersonaCfo", GOV + "inheritsFrom", GOV + "PersonaGuest",
                "cfo inheritsFrom guest (transitive, 3 hops)");
        // ...and the inverse gives the downward view.
        checkTriple(inf, GOV + "PersonaViewer", GOV + "inheritedBy", GOV + "PersonaCto",
                "viewer inheritedBy cto (inverse of transitive)");

        // 2. Domain hierarchy is transitive, and IT Spend has TWO parents on purpose.
        checkTriple(inf, GOV + "DomainItSpend", GOV + "subDomainOf", GOV + "DomainIt",
                "IT Spend subDomainOf IT");
        checkTriple(inf, GOV + "DomainItSpend", GOV + "subDomainOf", GOV + "DomainFinance",
                "IT Spend subDomainOf Finance (dual parent)");
        checkTriple(inf, GOV + "DomainFinance", GOV + "hasSubDomain", GOV + "DomainTreasury",
                "Finance hasSubDomain Treasury (inverse)");

        // 3. The owl:hasValue restriction pattern: an instance of a domain class
        //    must be INFERRED into its data domain and sensitivity with nothing
        //    asserted on the instance itself.
        Model probe = ModelFactory.createDefaultModel();
        probe.add(base);
        Resource line = probe.createResource("urn:probe:budget-line-1");
        line.addProperty(RDF.type, probe.createResource(FIN + "BudgetLine"));
        Resource emp = probe.createResource("urn:probe:employee-1");
        emp.addProperty(RDF.type, probe.createResource(HR + "Employee"));
        Resource inc = probe.createResource("urn:probe:incident-1");
        inc.addProperty(RDF.type, probe.createResource(EIP + "Incident"));
        Resource zcf = probe.createResource("urn:probe:zen-cashflow-1");
        zcf.addProperty(RDF.type, probe.createResource(ZEN + "CashFlowMonth"));
        InfModel pinf = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), probe);

        checkTriple(pinf, "urn:probe:budget-line-1", GOV + "inDataDomain", GOV + "DomainBudget",
                "a bare BudgetLine is classified into Budget domain");
        checkTriple(pinf, "urn:probe:budget-line-1", GOV + "hasSensitivity", GOV + "Confidential",
                "a bare BudgetLine is classified Confidential");
        checkTriple(pinf, "urn:probe:employee-1", GOV + "inDataDomain", GOV + "DomainHeadcount",
                "a bare hr:Employee is classified into Headcount domain");
        // hr:Employee is a subclass of ent:Person, so it must be inferred as one.
        checkTriple(pinf, "urn:probe:employee-1", RDF.type.getURI(), ENT + "Person",
                "hr:Employee is inferred to be an ent:Person");
        // eip-core's Incident gains classification from eip-itops WITHOUT eip-core
        // being modified.
        checkTriple(pinf, "urn:probe:incident-1", GOV + "inDataDomain", GOV + "DomainItOperations",
                "eip:Incident classified by eip-itops.owl (eip-core untouched)");
        // Zenitus alignment: a tenant-local class inherits the shared classification.
        checkTriple(pinf, "urn:probe:zen-cashflow-1", GOV + "inDataDomain", GOV + "DomainTreasury",
                "zen:CashFlowMonth inherits Treasury domain via alignment");
        checkTriple(pinf, "urn:probe:zen-cashflow-1", GOV + "hasSensitivity", GOV + "Confidential",
                "zen:CashFlowMonth inherits Confidential via alignment");

        // 3b. The OTHER core. eip-itops.owl classifies both the IT-estate namespace
        //     (.../eip#) and the work-item namespace (.../core#) that the reasoning
        //     service actually deploys. Missing the second one would leave every
        //     Jira issue in the graph unclassified and therefore unreasonable-about.
        Model cores = ModelFactory.createDefaultModel();
        cores.add(base);
        cores.createResource("urn:probe:issue-1")
                .addProperty(RDF.type, cores.createResource(CORE + "Issue"));
        // An INFERRED subclass: OverdueIssue is rdfs:subClassOf Issue, so it must
        // pick up Issue's classification without a restriction of its own.
        cores.createResource("urn:probe:overdue-1")
                .addProperty(RDF.type, cores.createResource(CORE + "OverdueIssue"));
        cores.createResource("urn:probe:coreperson-1")
                .addProperty(RDF.type, cores.createResource(CORE + "Person"));
        InfModel coreInf = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), cores);

        checkTriple(coreInf, "urn:probe:issue-1", GOV + "inDataDomain", GOV + "DomainItOperations",
                "core:Issue (work-item core) is classified");
        checkTriple(coreInf, "urn:probe:overdue-1", GOV + "inDataDomain", GOV + "DomainItOperations",
                "core:OverdueIssue inherits its parent's classification");
        checkTriple(coreInf, "urn:probe:coreperson-1", GOV + "inDataDomain", GOV + "DomainEnterprise",
                "core:Person is classified into Enterprise");

        // 4. The existing ent:reportsTo transitivity must still work for hr:Employee
        //    (that closure is what an OWN_REPORTS access scope resolves against),
        //    and Zenitus's zen:reportsTo must feed into it as a sub-property.
        Model chain = ModelFactory.createDefaultModel();
        chain.add(base);
        Resource a = chain.createResource("urn:probe:emp-a");
        Resource b = chain.createResource("urn:probe:emp-b");
        Resource c = chain.createResource("urn:probe:emp-c");
        Property reportsTo = chain.createProperty(ENT + "reportsTo");
        Property zenReportsTo = chain.createProperty(ZEN + "reportsTo");
        a.addProperty(RDF.type, chain.createResource(HR + "Employee"));
        a.addProperty(zenReportsTo, b);      // asserted with the TENANT property
        b.addProperty(reportsTo, c);         // asserted with the SHARED property
        InfModel cinf = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), chain);

        checkTriple(cinf, "urn:probe:emp-a", ENT + "reportsTo", "urn:probe:emp-b",
                "zen:reportsTo rolls up into ent:reportsTo (sub-property)");
        checkTriple(cinf, "urn:probe:emp-a", ENT + "reportsTo", "urn:probe:emp-c",
                "management chain closes across tenant + shared properties");
        checkTriple(cinf, "urn:probe:emp-c", ENT + "hasReport", "urn:probe:emp-a",
                "ent:hasReport gives the downward closure");

        // 5. Strategy contribution chain: milestone -> ... -> objective in one hop.
        Model sc = ModelFactory.createDefaultModel();
        sc.add(base);
        Property contributes = sc.createProperty("http://orphos.eip/ontology/strategy#contributesTo");
        Resource ms = sc.createResource("urn:probe:milestone-1");
        Resource pr = sc.createResource("urn:probe:project-1");
        Resource init = sc.createResource("urn:probe:initiative-1");
        Resource obj = sc.createResource("urn:probe:objective-1");
        ms.addProperty(contributes, pr);
        pr.addProperty(contributes, init);
        init.addProperty(contributes, obj);
        InfModel scinf = ModelFactory.createInfModel(ReasonerRegistry.getOWLReasoner(), sc);
        checkTriple(scinf, "urn:probe:milestone-1",
                "http://orphos.eip/ontology/strategy#contributesTo", "urn:probe:objective-1",
                "milestone contributesTo objective (3 hops, transitive)");
        checkTriple(scinf, "urn:probe:objective-1",
                "http://orphos.eip/ontology/strategy#contributedToBy", "urn:probe:milestone-1",
                "objective contributedToBy milestone (inverse closure)");

        // 6. Sanity: every Persona has a roleKey and a maxSensitivity â€” a persona
        //    without either can never be resolved from a JWT or enforced.
        System.out.println();
        System.out.println("== Vocabulary completeness ==");
        ResIterator personas = base.listSubjectsWithProperty(RDF.type, base.createResource(GOV + "Persona"));
        int pc = 0;
        while (personas.hasNext()) {
            Resource p = personas.nextResource();
            pc++;
            String label = p.getLocalName();
            require(p.hasProperty(base.createProperty(GOV + "roleKey")), label + " has roleKey");
            require(p.hasProperty(base.createProperty(GOV + "maxSensitivity")), label + " has maxSensitivity");
        }
        System.out.printf("  %d personas checked%n", pc);

        ResIterator widgets = base.listSubjectsWithProperty(RDF.type, base.createResource(GOV + "Widget"));
        int wc = 0;
        while (widgets.hasNext()) {
            Resource w = widgets.nextResource();
            wc++;
            require(w.hasProperty(base.createProperty(GOV + "widgetKey")),
                    w.getLocalName() + " has widgetKey");
            require(w.hasProperty(base.createProperty(GOV + "inDataDomain")),
                    w.getLocalName() + " has inDataDomain");
        }
        System.out.printf("  %d widgets checked%n", wc);

        ResIterator metrics = base.listSubjectsWithProperty(RDF.type, base.createResource(GOV + "Metric"));
        int mc = 0;
        while (metrics.hasNext()) {
            Resource m = metrics.nextResource();
            mc++;
            require(m.hasProperty(base.createProperty(GOV + "renderedBy")),
                    m.getLocalName() + " renders into a widget");
            require(m.hasProperty(base.createProperty(GOV + "inDataDomain")),
                    m.getLocalName() + " has inDataDomain");
            require(m.hasProperty(base.createProperty(GOV + "hasSensitivity")),
                    m.getLocalName() + " has sensitivity");
        }
        System.out.printf("  %d metrics checked%n", mc);

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * Parses RDF/XML with Jena's ARP parser directly instead of via
     * ModelCom.read / RDFDataMgr. RIOT's parser builder statically initialises
     * org.apache.jena.http.HttpEnv, which constructs a java.net.http.HttpClient
     * and therefore an NIO selector â€” and that needs a loopback socket, which is
     * blocked in this sandboxed shell ("Unable to establish loopback
     * connection"). The same restriction is why LoadEnterpriseProfile uses
     * HttpURLConnection rather than HttpClient. ARP does no networking, so it
     * parses the identical RDF/XML without touching HttpEnv.
     */
    static void readRdfXml(Model model, InputStream in, String base) throws Exception {
        ARP arp = new ARP();
        Map<String, Resource> blanks = new HashMap<>();
        StatementHandler handler = new StatementHandler() {
            @Override public void statement(AResource s, AResource p, AResource o) {
                model.add(res(model, s, blanks), model.createProperty(p.getURI()), res(model, o, blanks));
            }
            @Override public void statement(AResource s, AResource p, ALiteral o) {
                Literal lit;
                if (o.getDatatypeURI() != null) {
                    lit = model.createTypedLiteral(o.toString(),
                            org.apache.jena.datatypes.TypeMapper.getInstance().getSafeTypeByName(o.getDatatypeURI()));
                } else if (o.getLang() != null && !o.getLang().isEmpty()) {
                    lit = model.createLiteral(o.toString(), o.getLang());
                } else {
                    lit = model.createLiteral(o.toString());
                }
                model.add(res(model, s, blanks), model.createProperty(p.getURI()), lit);
            }
        };
        arp.getHandlers().setStatementHandler(handler);
        arp.load(in, base);
    }

    static Resource res(Model m, AResource a, Map<String, Resource> blanks) {
        if (a.isAnonymous()) {
            return blanks.computeIfAbsent(a.getAnonymousID(), k -> m.createResource());
        }
        return m.createResource(a.getURI());
    }

    static void checkTriple(Model m, String s, String p, String o, String what) {
        boolean ok = m.contains(m.createResource(s), m.createProperty(p), m.createResource(o));
        System.out.printf("  [%s] %s%n", ok ? "PASS" : "FAIL", what);
        if (!ok) failures++;
    }

    static void require(boolean cond, String what) {
        if (!cond) { System.out.printf("  [FAIL] %s%n", what); failures++; }
    }
}

