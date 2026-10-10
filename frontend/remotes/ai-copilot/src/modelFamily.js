/**
 * Derives the *model* family shown in usage/history views from the model name
 * that was actually sent to the runtime.
 *
 * Why this exists: `providerId` is a routing key, not a model label, and two of
 * the registered ids are historical misnomers — `gemma` is the Google AI Studio
 * connector (it happily serves `gemini-*` models) and `inhouse-hi` serves
 * Qwen2.5. Badging a row with the provider id therefore reported "gemma" next
 * to `gemini-3.6-flash`, and "inhouse-hi" next to `qwen2.5:1.5b`. The model
 * name is the authoritative field (nl-query-service records
 * `provider.resolveModelName(overrides)`), so the badge is derived from it and
 * the provider id is kept only as the routing detail.
 */

/** Ordered longest-prefix-first: `gemini` must be tested before `gem`-ish rules. */
const FAMILY_RULES = [
  [/^gemini/,               'Gemini'],
  [/^gemma/,                'Gemma'],
  [/^qwen/,                 'Qwen'],
  [/^phi/,                  'Phi'],
  [/^granite/,              'Granite'],
  [/^claude/,               'Claude'],
  [/^(llama|codellama)/,    'Llama'],
  [/^mixtral/,              'Mixtral'],
  [/^mistral/,              'Mistral'],
  [/^deepseek/,             'DeepSeek'],
  [/^(gpt|o[1-9](-|$))/,    'GPT'],
  [/^command/,              'Command'],
  [/^nomic|^bge/,           'Embedding'],
];

/** Provider ids whose runtime lives on the operator's own hardware. */
const LOCAL_PROVIDER_RE = /^inhouse/;

/**
 * Human label for the model family, e.g. `gemini-3.6-flash` → `Gemini`,
 * `qwen2.5:1.5b` → `Qwen`. Unknown models fall back to the leading word of the
 * model string; a missing model falls back to the provider id.
 */
export function modelFamily(modelName, providerId) {
  const model = (modelName || '').trim().toLowerCase();
  if (!model) return providerId || '—';

  for (const [re, label] of FAMILY_RULES) {
    if (re.test(model)) return label;
  }

  // Unknown family — take the leading alphabetic run (`foo3.1-dense:2b` → `Foo`)
  // so a newly configured model still gets a sensible badge without a code change.
  const lead = model.match(/^[a-z]+/);
  if (!lead) return providerId || model;
  return lead[0].charAt(0).toUpperCase() + lead[0].slice(1);
}

/** True when the model ran on the operator's own hardware rather than a cloud API. */
export function isLocalProvider(providerId) {
  return LOCAL_PROVIDER_RE.test((providerId || '').trim().toLowerCase());
}

/**
 * Tooltip copy for the badge: names the family, the exact model and the
 * routing provider id, so the id stays discoverable even though it is no
 * longer the visible label.
 */
export function modelBadgeTooltip(modelName, providerId) {
  const parts = [];
  if (modelName) parts.push(`Model: ${modelName}`);
  if (providerId) {
    parts.push(`Provider route: ${providerId}${isLocalProvider(providerId) ? ' (on-premise)' : ' (external API)'}`);
  }
  return parts.join(' · ') || 'No model recorded';
}
