package io.orphos.eip.mobilebff.component;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds demo rows for the new finance / HR / forecast types so the Financials and
 * Team Intelligence tiles show real data end-to-end. Gated by config and idempotent:
 * runs only when {@code mobile.demo.seed-enabled=true} and a {@code mobile.demo.tenant-id}
 * is set, and skips a type that already has rows for that tenant.
 *
 * These are typed demo doc types (not the gov-classified graph loaders a production
 * tenant would use); HR rows are CONSOLIDATED per org unit — never per person.
 */
@Component
@Order(2)
@ConditionalOnProperty(value = "mobile.demo.seed-enabled", havingValue = "true")
public class MobileDemoSeeder implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(MobileDemoSeeder.class);

    private final MobileArcadeClient arcade;
    private final String tenantId;

    public MobileDemoSeeder(MobileArcadeClient arcade,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${mobile.demo.tenant-id:}") String tenantId) {
        this.arcade = arcade;
        this.tenantId = tenantId;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tenantId == null || tenantId.isBlank()) {
            log.info("Mobile demo seeding skipped: mobile.demo.tenant-id is not set.");
            return;
        }
        log.info("Seeding mobile demo data for tenant {}...", tenantId);
        try {
            createTypes();
            seedFinance();
            seedForecast();
            seedTeam();
            log.info("Mobile demo data ready for tenant {}.", tenantId);
        } catch (Exception e) {
            log.error("Mobile demo seeding failed: {}", e.getMessage(), e);
        }
    }

    // ── schema ───────────────────────────────────────────────────────────────

    private void createTypes() {
        defineType("Portfolio", "name STRING", "portfolioType STRING", "baseCurrency STRING",
                "marketValue DOUBLE", "asOfDate STRING");
        defineType("PortfolioHolding", "portfolioId STRING", "name STRING", "assetClass STRING",
                "marketValue DOUBLE", "costBasis DOUBLE", "weightPct DOUBLE");
        defineType("CreditScore", "companyName STRING", "creditRating STRING",
                "creditScoreValue INTEGER", "creditProvider STRING", "asOfDate STRING");
        defineType("ForecastScenario", "name STRING", "scenarioType STRING",
                "revenue DOUBLE", "ebitda DOUBLE", "asOfDate STRING");
        defineType("HrProductivitySnapshot", "orgUnit STRING", "period STRING",
                "productivityIndex DOUBLE", "utilisationPct DOUBLE", "throughputCount INTEGER");
        defineType("HrWellbeingSnapshot", "orgUnit STRING", "period STRING",
                "wellbeingIndex DOUBLE", "overloadRatePct DOUBLE");
        defineType("HrAvailabilitySnapshot", "orgUnit STRING", "availableCount INTEGER",
                "busyCount INTEGER", "onLeaveCount INTEGER", "asOfInstant LONG");
    }

    private void defineType(String type, String... columns) {
        arcade.commandNoResult("CREATE DOCUMENT TYPE " + type + " IF NOT EXISTS", Map.of());
        tryExec("CREATE PROPERTY " + type + ".id STRING");
        tryExec("CREATE PROPERTY " + type + ".tenantId STRING");
        for (String c : columns) {
            tryExec("CREATE PROPERTY " + type + "." + c);
        }
        arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON " + type + " (id) UNIQUE", Map.of());
        arcade.commandNoResult(
                "CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId) NOTUNIQUE", Map.of());
    }

    // ── data ─────────────────────────────────────────────────────────────────

    private void seedFinance() {
        if (hasRows("Portfolio")) return;
        String pid = "pf-" + tenantId + "-1";
        insert("Portfolio", row("id", pid, "name", "Group Portfolio",
                "portfolioType", "STRATEGIC", "baseCurrency", "USD",
                "marketValue", 1_250_000_000d, "asOfDate", "2026-06-30"));
        insert("PortfolioHolding", row("id", pid + "-h1", "portfolioId", pid,
                "name", "Zenitus Manufacturing KK", "assetClass", "SUBSIDIARY",
                "marketValue", 720_000_000d, "costBasis", 500_000_000d, "weightPct", 57.6));
        insert("PortfolioHolding", row("id", pid + "-h2", "portfolioId", pid,
                "name", "Orphos Cloud Services", "assetClass", "SUBSIDIARY",
                "marketValue", 380_000_000d, "costBasis", 250_000_000d, "weightPct", 30.4));
        insert("PortfolioHolding", row("id", pid + "-h3", "portfolioId", pid,
                "name", "Treasury Fund", "assetClass", "FUND",
                "marketValue", 150_000_000d, "costBasis", 150_000_000d, "weightPct", 12.0));
        if (!hasRows("CreditScore")) {
            insert("CreditScore", row("id", "cs-" + tenantId + "-1",
                    "companyName", "Zenitus Group", "creditRating", "A+",
                    "creditScoreValue", 82, "creditProvider", "S&P", "asOfDate", "2026-06-30"));
        }
    }

    private void seedForecast() {
        if (hasRows("ForecastScenario")) return;
        insert("ForecastScenario", row("id", "fc-" + tenantId + "-base", "name", "FY26 Base",
                "scenarioType", "BASE", "revenue", 480_000_000d, "ebitda", 96_000_000d, "asOfDate", "2026-06-30"));
        insert("ForecastScenario", row("id", "fc-" + tenantId + "-up", "name", "FY26 Upside",
                "scenarioType", "UPSIDE", "revenue", 540_000_000d, "ebitda", 124_000_000d, "asOfDate", "2026-06-30"));
        insert("ForecastScenario", row("id", "fc-" + tenantId + "-down", "name", "FY26 Downside",
                "scenarioType", "DOWNSIDE", "revenue", 410_000_000d, "ebitda", 70_000_000d, "asOfDate", "2026-06-30"));
    }

    private void seedTeam() {
        List<String> units = List.of("Engineering", "Sales", "Operations");
        if (!hasRows("HrProductivitySnapshot")) {
            double[] prod = {87.5, 79.0, 82.3};
            double[] util = {91.0, 84.5, 88.0};
            int[] tput = {142, 96, 118};
            for (int i = 0; i < units.size(); i++) {
                insert("HrProductivitySnapshot", row("id", "prod-" + tenantId + "-" + i,
                        "orgUnit", units.get(i), "period", "2026-06",
                        "productivityIndex", prod[i], "utilisationPct", util[i], "throughputCount", tput[i]));
            }
        }
        if (!hasRows("HrWellbeingSnapshot")) {
            double[] well = {74.0, 68.5, 71.2};
            double[] overload = {12.0, 21.0, 15.5};
            for (int i = 0; i < units.size(); i++) {
                insert("HrWellbeingSnapshot", row("id", "well-" + tenantId + "-" + i,
                        "orgUnit", units.get(i), "period", "2026-06",
                        "wellbeingIndex", well[i], "overloadRatePct", overload[i]));
            }
        }
        if (!hasRows("HrAvailabilitySnapshot")) {
            // Consolidated counts per org unit — never per-person leave records.
            int[] avail = {38, 22, 27};
            int[] busy = {9, 6, 5};
            int[] leave = {3, 2, 4};
            for (int i = 0; i < units.size(); i++) {
                insert("HrAvailabilitySnapshot", row("id", "avail-" + tenantId + "-" + i,
                        "orgUnit", units.get(i), "availableCount", avail[i],
                        "busyCount", busy[i], "onLeaveCount", leave[i], "asOfInstant", 0L));
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private boolean hasRows(String type) {
        try {
            List<Map<String, Object>> r = arcade.query(
                    "SELECT count(*) AS c FROM " + type + " WHERE tenantId = :t", Map.of("t", tenantId));
            return !r.isEmpty() && r.get(0).get("c") instanceof Number n && n.longValue() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void insert(String type, Map<String, Object> fields) {
        fields.put("tenantId", tenantId);
        StringBuilder sets = new StringBuilder();
        for (String k : fields.keySet()) {
            if (sets.length() > 0) sets.append(", ");
            sets.append(k).append(" = :").append(k);
        }
        arcade.commandNoResult("INSERT INTO " + type + " SET " + sets, fields);
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private void tryExec(String sql) {
        try {
            arcade.commandNoResult(sql, Map.of());
        } catch (Exception e) {
            log.debug("DDL skipped (likely already exists): {} — {}", sql.trim(), e.getMessage());
        }
    }
}
