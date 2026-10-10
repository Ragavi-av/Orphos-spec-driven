package io.orphos.eip.dataingestion.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Column-name based masking for data previews (requirement 8.5). A column is
 * sensitive when its name contains any configured pattern fragment
 * (case-insensitive substring — {@code USER_PASSWORD} matches {@code password}).
 *
 * <p>Masking is deliberately blunt and fails closed: the first character is
 * kept as a recognisability hint, everything else becomes {@code ***}. Email
 * columns keep the shape hint {@code a***@***}. Original lengths, formats and
 * distributions are never preserved.</p>
 */
@Component
public class SensitiveDataMasker {

    private final List<String> patterns;

    public SensitiveDataMasker(
            @Value("${ingestion.preview.sensitive-patterns:password,secret,token,ssn,credit,card,iban,salary,dob,birth,email,phone}")
            List<String> patterns) {
        this.patterns = patterns.stream()
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .filter(p -> !p.isEmpty())
                .toList();
    }

    /** True when the column name matches any configured sensitive pattern. */
    public boolean isSensitive(String columnName) {
        if (columnName == null) return true; // fail closed on the pathological case
        String lower = columnName.toLowerCase(Locale.ROOT);
        return patterns.stream().anyMatch(lower::contains);
    }

    /**
     * Masked rendering of a value: {@code null} stays {@code null}, everything
     * else keeps only its first character — {@code J***}, emails {@code j***@***}.
     */
    public String mask(String columnName, Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        if (s.isEmpty()) return "***";
        String first = s.substring(0, 1);
        boolean emailShaped = (columnName != null && columnName.toLowerCase(Locale.ROOT).contains("email"))
                || s.indexOf('@') > 0;
        return emailShaped ? first + "***@***" : first + "***";
    }
}
