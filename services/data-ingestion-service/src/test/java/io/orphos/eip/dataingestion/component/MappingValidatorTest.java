package io.orphos.eip.dataingestion.component;

import io.orphos.eip.dataingestion.util.TransformFunctions;
import io.orphos.eip.dataingestion.vo.MappingDtos.ColumnMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.SaveMappingRequest;
import io.orphos.eip.dataingestion.vo.MappingDtos.TargetType;
import io.orphos.eip.dataingestion.vo.MappingDtos.ValidationReport;
import io.orphos.eip.dataingestion.service.MetadataDiscoveryService;
import io.orphos.eip.dataingestion.vo.MetadataDtos.ColumnInfo;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import io.orphos.eip.dataingestion.vo.MetadataDtos.TableInfo;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Requirement 3 acceptance checks for mapping validation and transforms. */
class MappingValidatorTest {

    private final MappingValidator validator = new MappingValidator(metadataWith(
            new TableInfo("public", "customers", "TABLE", 3,
                    List.of(
                            col("id", "int4", Types.INTEGER),
                            col("first_name", "varchar", Types.VARCHAR),
                            col("last_name", "varchar", Types.VARCHAR),
                            col("created_at", "timestamp", Types.TIMESTAMP)),
                    List.of(), List.of())));

    private static MetadataDiscoveryService metadataWith(TableInfo table) {
        MetadataDiscoveryService md = mock(MetadataDiscoveryService.class);
        when(md.getSnapshot(anyString(), anyBoolean())).thenReturn(
                new SchemaSnapshot("ds-1", "now", false, "PostgreSQL", List.of(table), List.of()));
        return md;
    }

    private static ColumnInfo col(String name, String typeName, int jdbc) {
        return new ColumnInfo(name, typeName, jdbc, 0, true, false, false);
    }

    private static SaveMappingRequest req(String keyTarget, List<ColumnMapping> mappings) {
        return new SaveMappingRequest("Customers", "ds-1", "public", "customers",
                "Customer", "DOCUMENT", keyTarget, null, mappings, List.of(), true);
    }

    @Test
    void validOneToOneAndManyToOneMappingPasses() {
        ValidationReport r = validator.validate(req("custId", List.of(
                new ColumnMapping("custId", List.of("id"), "INTEGER", null),          // 1:1 (req 3.2)
                new ColumnMapping("fullName", List.of("first_name", "last_name"),      // many:1 (req 3.3)
                        "STRING", "CONCAT: "))));
        assertThat(r.errors()).isEmpty();
        assertThat(r.valid()).isTrue();
    }

    @Test
    void unknownSourceColumnIsAnError() {
        ValidationReport r = validator.validate(req(null, List.of(
                new ColumnMapping("x", List.of("no_such_column"), null, null))));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("no_such_column"));
    }

    @Test
    void incompatibleTypeMappingProducesWarning() {
        // varchar → INTEGER: requirement 3.5 validation warning, not an error.
        ValidationReport r = validator.validate(req(null, List.of(
                new ColumnMapping("age", List.of("first_name"), "INTEGER", null))));
        assertThat(r.valid()).isTrue();
        assertThat(r.warnings()).anySatisfy(w ->
                assertThat(w).contains("first_name").contains("INTEGER"));
    }

    @Test
    void unmappedKeyTargetIsAnError() {
        // Requirement 3.7 — the required (key) target must have a mapping.
        ValidationReport r = validator.validate(req("custId", List.of(
                new ColumnMapping("fullName", List.of("first_name"), null, null))));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("custId"));
    }

    @Test
    void unknownTransformIsAnError() {
        ValidationReport r = validator.validate(req(null, List.of(
                new ColumnMapping("x", List.of("id"), null, "FROBNICATE"))));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("FROBNICATE"));
    }

    @Test
    void duplicateTargetIsAnError() {
        ValidationReport r = validator.validate(req(null, List.of(
                new ColumnMapping("x", List.of("id"), null, null),
                new ColumnMapping("x", List.of("first_name"), null, null))));
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("mapped more than once"));
    }

    // --- transforms (req 3.4 / 3.6) ----------------------------------------

    @Test
    void transformsApplyAndConvert() {
        assertThat(TransformFunctions.apply("  jo  ", "TRIM")).isEqualTo("jo");
        assertThat(TransformFunctions.apply("jo", "UPPER")).isEqualTo("JO");
        assertThat(TransformFunctions.apply("Jo", "PREFIX:Dr. ")).isEqualTo("Dr. Jo");
        assertThat(TransformFunctions.apply("a-b", "REPLACE:-=_")).isEqualTo("a_b");
        assertThat(TransformFunctions.apply("abcdef", "SUBSTRING:1,3")).isEqualTo("bc");
        assertThat(TransformFunctions.apply("20260710", "DATE_PATTERN:yyyyMMdd"))
                .isEqualTo("2026-07-10");

        assertThat(TransformFunctions.combine(List.of("Ada", "Lovelace"), "CONCAT: "))
                .isEqualTo("Ada Lovelace");

        assertThat(TransformFunctions.convert("42", TargetType.INTEGER)).isEqualTo(42L);
        assertThat(TransformFunctions.convert("4.5", TargetType.NUMBER)).isEqualTo(4.5d);
        assertThat(TransformFunctions.convert("yes", TargetType.BOOLEAN)).isEqualTo(true);
        assertThat(TransformFunctions.convert(" x ", TargetType.STRING)).isEqualTo("x");
    }
}
