package io.orphos.eip.admin.onboarding.infer;

import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.ColumnInfo;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.ColumnRole;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.ColumnType;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.EntityModel;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.RelationshipModel;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.WidgetProposal;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.WidgetType;
import io.orphos.eip.admin.onboarding.parse.ParsedTable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Infers an entity/column model + widget proposals from parsed tables. Pure
 * heuristics over the data — no LLM — so it is deterministic and reviewable.
 */
@Component
public class SchemaInferencer {

    private static final String[] PALETTE = {
            "#2563eb", "#7c3aed", "#16a34a", "#d97706", "#dc2626", "#0891b2", "#db2777", "#0d9488"
    };
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]+");
    private static final int MAX_WIDGETS = 12;
    private static final int CATEGORY_MAX_DISTINCT = 25;

    public record InferenceModel(List<EntityModel> entities,
                                 List<RelationshipModel> relationships,
                                 List<WidgetProposal> widgets,
                                 List<String> warnings) {}

    public InferenceModel infer(List<ParsedTable> tables) {
        List<EntityModel> entities = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> usedClassNames = new LinkedHashSet<>();

        for (ParsedTable t : tables) {
            if (t.headers().isEmpty()) { warnings.add("Skipped a sheet/file with no header row."); continue; }
            if (t.rows().isEmpty())    { warnings.add("Entity '" + entityLabel(t) + "' has no data rows."); }

            String className = uniqueClassName(classNameFor(t), usedClassNames);
            List<ColumnInfo> columns = new ArrayList<>();
            for (int c = 0; c < t.headers().size(); c++) {
                columns.add(inferColumn(t, c));
            }
            String idProp = columns.stream()
                    .filter(col -> col.role() == ColumnRole.ID)
                    .map(ColumnInfo::property).findFirst().orElse(null);

            entities.add(new EntityModel(className, entityLabel(t), t.sourceFile(),
                    t.sheetName(), t.rows().size(), idProp, columns));
        }

        List<RelationshipModel> relationships = inferRelationships(entities);
        List<WidgetProposal> widgets = proposeWidgets(entities);
        return new InferenceModel(entities, relationships, widgets, warnings);
    }

    // ── Column inference ──────────────────────────────────────────────────────

    private ColumnInfo inferColumn(ParsedTable t, int colIdx) {
        String header = t.headers().get(colIdx);
        String property = propertyName(header);

        List<String> values = new ArrayList<>();
        Set<String> distinct = new LinkedHashSet<>();
        for (List<String> row : t.rows()) {
            String v = colIdx < row.size() ? row.get(colIdx) : null;
            if (v != null && !v.isBlank()) { values.add(v); distinct.add(v); }
        }
        List<String> samples = values.stream().distinct().limit(3).toList();

        ColumnType type = inferType(values);
        ColumnRole role = inferRole(header, type, distinct.size(), values.size());
        return new ColumnInfo(header, property, type, role, distinct.size(), samples);
    }

    private ColumnType inferType(List<String> values) {
        if (values.isEmpty()) return ColumnType.STRING;
        boolean allInt = true, allNum = true, allDate = true, allBool = true;
        for (String v : values) {
            String s = v.trim();
            if (allBool && !s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")
                    && !s.equalsIgnoreCase("yes") && !s.equalsIgnoreCase("no")) allBool = false;
            String num = s.replace(",", "");
            if (allInt) { try { Long.parseLong(num); } catch (Exception e) { allInt = false; } }
            if (allNum) { try { Double.parseDouble(num); } catch (Exception e) { allNum = false; } }
            if (allDate && !isDate(s)) allDate = false;
        }
        if (allBool) return ColumnType.BOOLEAN;
        if (allInt)  return ColumnType.INTEGER;
        if (allNum)  return ColumnType.NUMBER;
        if (allDate) return ColumnType.DATE;
        return ColumnType.STRING;
    }

    private boolean isDate(String s) {
        try { LocalDate.parse(s); return true; } catch (Exception ignored) {}
        return false;
    }

    private ColumnRole inferRole(String header, ColumnType type, int distinct, int total) {
        String h = header.toLowerCase(Locale.ENGLISH);
        boolean idName = h.equals("id") || h.endsWith(" id") || h.endsWith("_id")
                || h.contains("code") || h.endsWith(" no") || h.endsWith(" no.")
                || h.contains("number") || h.contains("serial");
        boolean unique = total > 0 && distinct >= total * 0.9;
        if (idName && unique) return ColumnRole.ID;
        if (type == ColumnType.DATE) return ColumnRole.DATE;
        if (type == ColumnType.INTEGER || type == ColumnType.NUMBER) {
            // A unique-ish integer that looks like a key is an ID, not a measure.
            return (idName && unique) ? ColumnRole.ID : ColumnRole.MEASURE;
        }
        // STRING / BOOLEAN → low cardinality ⇒ category, else free text.
        if (distinct > 0 && distinct <= CATEGORY_MAX_DISTINCT && distinct < total) return ColumnRole.CATEGORY;
        return ColumnRole.TEXT;
    }

    // ── Relationships ───────────────────────────────────────────────────────

    private List<RelationshipModel> inferRelationships(List<EntityModel> entities) {
        List<RelationshipModel> rels = new ArrayList<>();
        for (EntityModel from : entities) {
            for (ColumnInfo col : from.columns()) {
                if (col.role() != ColumnRole.CATEGORY && col.role() != ColumnRole.ID) continue;
                String base = col.property().replaceAll("(?i)id$", "");
                for (EntityModel to : entities) {
                    if (to == from) continue;
                    if (base.equalsIgnoreCase(to.className()) || col.property().equalsIgnoreCase(to.className())) {
                        rels.add(new RelationshipModel(
                                "has" + to.className(), from.className(), to.className(), col.property()));
                    }
                }
            }
        }
        return rels;
    }

    // ── Widget proposals ──────────────────────────────────────────────────────

    private List<WidgetProposal> proposeWidgets(List<EntityModel> entities) {
        List<WidgetProposal> widgets = new ArrayList<>();
        int color = 0;
        for (EntityModel e : entities) {
            String cl = e.className();
            String lc = cl.toLowerCase(Locale.ENGLISH);

            widgets.add(new WidgetProposal("count-" + lc, "Total " + e.label(),
                    WidgetType.COUNT, cl, null, null, null, color(color++), false));

            int measures = 0;
            for (ColumnInfo col : e.columns()) {
                if (col.role() == ColumnRole.MEASURE && measures < 3) {
                    widgets.add(new WidgetProposal("sum-" + lc + "-" + col.property(),
                            "Total " + humanize(col.name()), WidgetType.SUM, cl, col.property(),
                            null, null, color(color++), false));
                    measures++;
                }
            }
            int cats = 0;
            for (ColumnInfo col : e.columns()) {
                if (col.role() == ColumnRole.CATEGORY && cats < 2) {
                    widgets.add(new WidgetProposal("by-" + lc + "-" + col.property(),
                            e.label() + " by " + humanize(col.name()), WidgetType.BREAKDOWN, cl,
                            null, col.property(), null, color(color++), false));
                    cats++;
                }
            }
            ColumnInfo date = e.columns().stream().filter(c -> c.role() == ColumnRole.DATE).findFirst().orElse(null);
            ColumnInfo measure = e.columns().stream().filter(c -> c.role() == ColumnRole.MEASURE).findFirst().orElse(null);
            if (date != null && measure != null) {
                widgets.add(new WidgetProposal("trend-" + lc + "-" + measure.property(),
                        humanize(measure.name()) + " over time", WidgetType.TREND, cl,
                        measure.property(), null, date.property(), color(color++), false));
            }
        }
        if (widgets.size() > MAX_WIDGETS) widgets = new ArrayList<>(widgets.subList(0, MAX_WIDGETS));
        // Mark the first four as the tenant's default-visible set.
        for (int i = 0; i < widgets.size() && i < 4; i++) {
            WidgetProposal w = widgets.get(i);
            widgets.set(i, new WidgetProposal(w.id(), w.label(), w.type(), w.entity(),
                    w.column(), w.groupBy(), w.dateColumn(), w.color(), true));
        }
        return widgets;
    }

    // ── Naming helpers ──────────────────────────────────────────────────────

    private String entityLabel(ParsedTable t) {
        String s = (t.sheetName() != null && !isGenericSheet(t.sheetName()))
                ? t.sheetName() : baseFileName(t.sourceFile());
        return s == null || s.isBlank() ? "Entity" : s.trim();
    }

    private String classNameFor(ParsedTable t) {
        String label = entityLabel(t);
        String cleaned = label.replaceAll("(?i)[ _-]*data$", "");   // drop trailing "Data"
        String pascal = pascalCase(cleaned.isBlank() ? label : cleaned);
        return pascal.isEmpty() ? "Entity" : pascal;
    }

    private boolean isGenericSheet(String name) {
        return name.matches("(?i)sheet\\d*") || name.isBlank();
    }

    private String baseFileName(String file) {
        if (file == null) return null;
        String base = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
        base = base.contains("\\") ? base.substring(base.lastIndexOf('\\') + 1) : base;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base.replaceAll("\\s*\\(\\d+\\)\\s*$", "");   // strip " (1)" copy suffix
    }

    private String pascalCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : NON_ALNUM.split(s)) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }

    private String propertyName(String header) {
        String[] parts = NON_ALNUM.split(header.trim());
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (first) { sb.append(Character.toLowerCase(part.charAt(0)));
                         if (part.length() > 1) sb.append(part.substring(1)); first = false; }
            else { sb.append(Character.toUpperCase(part.charAt(0)));
                   if (part.length() > 1) sb.append(part.substring(1)); }
        }
        String p = sb.toString();
        return p.isEmpty() ? "field" : p;
    }

    private String humanize(String header) {
        return header == null ? "" : header.trim();
    }

    private String uniqueClassName(String base, Set<String> used) {
        String name = base;
        int n = 2;
        while (!used.add(name)) name = base + n++;
        return name;
    }

    private String color(int i) {
        return PALETTE[i % PALETTE.length];
    }
}
