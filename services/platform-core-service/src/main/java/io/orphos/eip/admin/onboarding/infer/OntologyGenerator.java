package io.orphos.eip.admin.onboarding.infer;

import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.ColumnInfo;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.ColumnType;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.EntityModel;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.RelationshipModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates an OWL/RDF-XML ontology (TBox) from inferred entities + relationships,
 * following the same shape as {@code ontology/eip-core.owl}: one owl:Class per
 * entity, owl:DatatypeProperty per column, owl:ObjectProperty per relationship.
 */
@Component
public class OntologyGenerator {

    private static final String NS = "https://orphos.io/ontology/data#";
    private static final String BASE = "https://orphos.io/ontology/data";

    public String generate(List<EntityModel> entities, List<RelationshipModel> relationships) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?>\n");
        sb.append("<!-- Auto-generated from uploaded tenant data. TBox only — no PII. -->\n");
        sb.append("<rdf:RDF xmlns=\"").append(NS).append("\"\n")
          .append("     xml:base=\"").append(BASE).append("\"\n")
          .append("     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n")
          .append("     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n")
          .append("     xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\"\n")
          .append("     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n\n");
        sb.append("    <owl:Ontology rdf:about=\"").append(BASE).append("\"/>\n\n");

        sb.append("    <!-- Classes -->\n");
        for (EntityModel e : entities) {
            sb.append("    <owl:Class rdf:about=\"#").append(e.className()).append("\">\n")
              .append("        <rdfs:label>").append(esc(e.label())).append("</rdfs:label>\n")
              .append("    </owl:Class>\n");
        }

        sb.append("\n    <!-- Datatype properties -->\n");
        for (EntityModel e : entities) {
            for (ColumnInfo col : e.columns()) {
                String propId = e.className() + "_" + col.property();
                sb.append("    <owl:DatatypeProperty rdf:about=\"#").append(propId).append("\">\n")
                  .append("        <rdfs:label>").append(esc(col.name())).append("</rdfs:label>\n")
                  .append("        <rdfs:domain rdf:resource=\"#").append(e.className()).append("\"/>\n")
                  .append("        <rdfs:range rdf:resource=\"").append(xsd(col.type())).append("\"/>\n")
                  .append("    </owl:DatatypeProperty>\n");
            }
        }

        if (relationships != null && !relationships.isEmpty()) {
            sb.append("\n    <!-- Object properties (relationships) -->\n");
            for (RelationshipModel r : relationships) {
                sb.append("    <owl:ObjectProperty rdf:about=\"#").append(r.name()).append("\">\n")
                  .append("        <rdfs:domain rdf:resource=\"#").append(r.fromClass()).append("\"/>\n")
                  .append("        <rdfs:range rdf:resource=\"#").append(r.toClass()).append("\"/>\n")
                  .append("    </owl:ObjectProperty>\n");
            }
        }

        sb.append("\n</rdf:RDF>\n");
        return sb.toString();
    }

    private String xsd(ColumnType t) {
        return switch (t) {
            case INTEGER -> "http://www.w3.org/2001/XMLSchema#integer";
            case NUMBER  -> "http://www.w3.org/2001/XMLSchema#decimal";
            case DATE    -> "http://www.w3.org/2001/XMLSchema#date";
            case BOOLEAN -> "http://www.w3.org/2001/XMLSchema#boolean";
            case STRING  -> "http://www.w3.org/2001/XMLSchema#string";
        };
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
