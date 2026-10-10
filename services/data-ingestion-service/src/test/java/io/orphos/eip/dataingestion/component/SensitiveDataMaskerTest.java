package io.orphos.eip.dataingestion.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 8.5 — sensitive-column detection and value masking. */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker(List.of(
            "password", "secret", "token", "ssn", "credit", "card", "iban",
            "salary", "dob", "birth", "email", "phone"));

    @Test
    void matchesPatternsCaseInsensitivelyAsSubstrings() {
        assertThat(masker.isSensitive("password")).isTrue();
        assertThat(masker.isSensitive("USER_PASSWORD")).isTrue();      // substring
        assertThat(masker.isSensitive("Email")).isTrue();              // case-insensitive
        assertThat(masker.isSensitive("phone_number")).isTrue();
        assertThat(masker.isSensitive("annualSalary")).isTrue();
        assertThat(masker.isSensitive("date_of_birth")).isTrue();
        assertThat(masker.isSensitive("CreditCardNo")).isTrue();
    }

    @Test
    void nonSensitiveColumnsPassThrough() {
        assertThat(masker.isSensitive("name")).isFalse();
        assertThat(masker.isSensitive("city")).isFalse();
        assertThat(masker.isSensitive("order_total")).isFalse();
        assertThat(masker.isSensitive("id")).isFalse();
    }

    @Test
    void failsClosedOnNullColumnName() {
        assertThat(masker.isSensitive(null)).isTrue();
    }

    @Test
    void nullValueStaysNull() {
        assertThat(masker.mask("ssn", null)).isNull();
    }

    @Test
    void masksToFirstCharPlusStars() {
        assertThat(masker.mask("ssn", "123-45-6789")).isEqualTo("1***");
        assertThat(masker.mask("salary", 85000)).isEqualTo("8***");
        assertThat(masker.mask("password", "hunter2")).isEqualTo("h***");
    }

    @Test
    void masksEmailsWithShapeHint() {
        assertThat(masker.mask("email", "alice@example.com")).isEqualTo("a***@***");
        assertThat(masker.mask("CONTACT_EMAIL", "bob@x.io")).isEqualTo("b***@***");
        // Email-shaped value in a non-email column still gets the email mask.
        assertThat(masker.mask("login", "carol@corp.net")).isEqualTo("c***@***");
    }

    @Test
    void emptyValueMasksFully() {
        assertThat(masker.mask("token", "")).isEqualTo("***");
    }

    @Test
    void configuredPatternsAreTrimmedAndCaseNormalised() {
        SensitiveDataMasker custom = new SensitiveDataMasker(List.of(" TaxCode ", ""));
        assertThat(custom.isSensitive("employee_taxcode")).isTrue();
        assertThat(custom.isSensitive("name")).isFalse();
    }
}
