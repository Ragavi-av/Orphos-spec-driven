import { useTranslation } from 'react-i18next';
import PromptKindsEditor from 'eip-shell-context/PromptKindsEditor';

// The communication-channel AI prompt kinds owned by this tab. They live in the
// same versioned nl-query prompt store as the other AI prompts and are consumed
// by mail-insight-service (channel-message classification during scans, and
// reply drafting for the channel AI actions) via nl-query's internal endpoint.
const KINDS = [
  { kind: 'comms-classification', label: 'Channel Classification',
    blurb: 'Triage prompt refining the rule-based verdict, summary and rationale for messages ingested from '
      + 'Slack, Microsoft Teams and other chat channels. Keep the JSON schema and the {{ITEM_TYPE}}, '
      + '{{BASE_KIND}}, {{BASE_PRIORITY}}, {{BASE_CRITICALITY}}, {{SUBJECT}}, {{FROM}} and {{PREVIEW}} placeholders.' },
  { kind: 'comms-drafting', label: 'Channel Reply Drafting',
    blurb: 'Reply drafting for the "AI: Draft channel reply" action on channel items. '
      + 'Keep the {{CHANNEL}}, {{FROM}}, {{BODY}} and {{INSTRUCTION}} placeholders.' },
];

/**
 * Administration → AI → Communications AI.
 *
 * Same versioning/rollback semantics as the other prompt tabs — this surface
 * scopes the shared editor to the communication-channel AI prompt kinds. Which
 * provider/model serves these functions is assigned per usage type in
 * Data & AI → AI Providers (Channel Classification / Channel Drafting).
 */
export default function CommsAITab() {
  const { t } = useTranslation();
  return (
    <PromptKindsEditor
      kinds={KINDS}
      scopeResource={t('admin.screens.commsAi.scopeResource', 'communications AI prompts')}
      title={t('admin.screens.commsAi.title', 'Communications AI')}
      intro={t('admin.screens.commsAi.intro',
        'Edit the prompts used to classify messages ingested from communication channels (Slack, Microsoft Teams, …) '
        + 'and to draft replies. Saving creates a new version; you can roll back from each prompt\'s history. '
        + 'Changes apply without a restart. Pick the provider/model for these functions under Data & AI → AI Providers.')}
      placeholderHint={t('admin.screens.commsAi.placeholderHint',
        'The {{…}} placeholders shown in each group\'s description are substituted at runtime — keep the ones the function needs.')}
    />
  );
}
