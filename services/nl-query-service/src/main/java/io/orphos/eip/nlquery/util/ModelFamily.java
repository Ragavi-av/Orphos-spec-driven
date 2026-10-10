package io.orphos.eip.nlquery.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Derives the model family label from the model name a provider actually
 * resolves to.
 *
 * <p>A provider id is a routing key, not a model label, and two of the
 * registered ids are historical misnomers: {@code gemma} is the Google AI
 * Studio connector and happily serves {@code gemini-*} models, while
 * {@code inhouse-hi} serves Qwen2.5. Any surface that labels a call by
 * provider id therefore reports the wrong model family. The resolved model
 * name is the authoritative value, so the family is derived from it.</p>
 */
public final class ModelFamily {

    private ModelFamily() {}

    /** Ordered longest-prefix-first: {@code gemini} must be tested before {@code gemma}. */
    private static final List<Rule> RULES = List.of(
            new Rule("^gemini",            "Gemini"),
            new Rule("^gemma",             "Gemma"),
            new Rule("^qwen",              "Qwen"),
            new Rule("^phi",               "Phi"),
            new Rule("^granite",           "Granite"),
            new Rule("^claude",            "Claude"),
            new Rule("^(llama|codellama)", "Llama"),
            new Rule("^mixtral",           "Mixtral"),
            new Rule("^mistral",           "Mistral"),
            new Rule("^deepseek",          "DeepSeek"),
            new Rule("^(gpt|o[1-9](-|$))", "GPT"),
            new Rule("^command",           "Command"),
            new Rule("^(nomic|bge)",       "Embedding"));

    private static final Pattern LEADING_WORD = Pattern.compile("^[a-z]+");

    /**
     * Human family label for a model name — {@code gemini-3.6-flash} → {@code Gemini},
     * {@code qwen2.5:1.5b} → {@code Qwen}. An unrecognised model falls back to its
     * leading word (so a newly configured model still labels sensibly without a code
     * change); a blank model falls back to {@code providerId}.
     */
    public static String of(String modelName, String providerId) {
        String model = modelName == null ? "" : modelName.trim().toLowerCase();
        if (model.isEmpty()) return providerId == null ? "—" : providerId;

        for (Rule r : RULES) {
            if (r.pattern().matcher(model).find()) return r.label();
        }

        var m = LEADING_WORD.matcher(model);
        if (!m.find()) return providerId == null ? model : providerId;
        String lead = m.group();
        return Character.toUpperCase(lead.charAt(0)) + lead.substring(1);
    }

    private record Rule(Pattern pattern, String label) {
        Rule(String regex, String label) {
            this(Pattern.compile(regex), label);
        }
    }
}
