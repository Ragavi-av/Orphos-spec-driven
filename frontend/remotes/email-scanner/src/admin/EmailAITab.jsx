import { useTranslation } from 'react-i18next';
import PromptKindsEditor from 'eip-shell-context/PromptKindsEditor';

// The email AI prompt kinds owned by this tab. They live in the same
// versioned nl-query prompt store as the AI Co-pilot prompts and are consumed
// by mail-insight-service (classification during mailbox scans, drafting for
// the AI Actions dialog) via nl-query's internal prompt endpoint.
const KINDS = [
  { kind: 'email-classification', label: 'Email Classification',
    blurb: 'Triage prompt refining the rule-based verdict, summary and rationale for monitored mailbox items. '
      + 'Keep the JSON schema and the {{ITEM_TYPE}}, {{BASE_KIND}}, {{BASE_PRIORITY}}, {{BASE_CRITICALITY}}, '
      + '{{SUBJECT}}, {{FROM}} and {{PREVIEW}} placeholders.' },
  { kind: 'email-drafting', label: 'Email Drafting',
    blurb: 'Reply and meeting-request drafting for the AI Actions in the Notifications tab. '
      + 'Keep the JSON response contract and the {{EXECUTIVE}}, {{FROM}}, {{SUBJECT}}, {{SUMMARY}}, '
      + '{{INSTRUCTION}} (and {{TODAY}} for meetings) placeholders.' },
];

/**
 * Administration → Communications → Email AI.
 *
 * Same versioning/rollback semantics as the Data & AI "LLM Prompts" tab —
 * this surface just scopes the shared editor to the email AI prompt kinds.
 * Which provider/model serves these functions is assigned per usage type in
 * Data & AI → AI Providers (Email Classification / Email Agent Drafting).
 */
export default function EmailAITab() {
  const { t } = useTranslation();
  return (
    <PromptKindsEditor
      kinds={KINDS}
      scopeResource={t('admin.screens.emailAi.scopeResource', 'email AI prompts')}
      title={t('admin.screens.emailAi.title', 'Email AI')}
      intro={t('admin.screens.emailAi.intro',
        'Edit the prompts used to classify monitored mailbox items and to draft replies and meeting requests. '
        + 'Saving creates a new version; you can roll back from each prompt\'s history. Changes apply without a restart. '
        + 'Pick the provider/model for these functions under Data & AI → AI Providers.')}
      placeholderHint={t('admin.screens.emailAi.placeholderHint',
        'The {{…}} placeholders shown in each group\'s description are substituted at runtime — keep the ones the function needs.')}
    />
  );
}
