import { useTranslation } from 'react-i18next';
import PromptKindsEditor from 'eip-shell-context/PromptKindsEditor';

// Display metadata per prompt "kind". Order here drives the on-screen order.
// The email-* and doc-processing kinds are deliberately NOT listed — they are
// owned by the Communications "Email AI" and Document Ingestion "Document AI"
// tabs, which render the same shared editor with their own kind subsets.
const KINDS = [
  { kind: 'briefing',         label: 'Executive Briefing — Rules',
    blurb: 'Ordered fragments assembled into the main briefing system prompt. Add, remove or reorder rules.' },
  { kind: 'briefing-frame',   label: 'Executive Briefing — Structure',
    blurb: 'Wrappers around the briefing context. Keep the {{RAG_CONTEXT}}, {{LIVE_CONTEXT}} and {{QUESTION}} placeholders.' },
  { kind: 'scope-classifier', label: 'Scope Classifier',
    blurb: 'The short RELEVANT/IRRELEVANT gate run before every briefing. Keep the {{QUESTION}} placeholder.' },
  { kind: 'provider-suffix',  label: 'Provider Guidance Suffixes',
    blurb: 'Extra guidance appended after the briefing for a specific provider.' },
  { kind: 'fallback',         label: 'Fallback Messages',
    blurb: 'Canned text used when the model returns nothing usable, or for the scope decline.' },
  { kind: 'tuning',           label: 'Model Tuning',
    blurb: 'Per-provider numeric settings (temperature, context size, output length, timeout). Applied without a restart.' },
];

/**
 * Admin-only tab for editing the LLM prompts & tuning used by nl-query-service.
 * Prompts are versioned in ArcadeDB; editing creates a new active version and an
 * older version can be re-activated (rollback). Changes apply without a restart.
 * The actual editor lives in eip-shell-context/PromptKindsEditor, shared with
 * the Email AI and Document AI admin tabs.
 */
export default function LLMPromptsTab() {
  const { t } = useTranslation();
  return (
    <PromptKindsEditor
      kinds={KINDS}
      defaultExpandedKind='briefing'
      scopeResource={t('admin.screens.prompts.scopeResource', 'LLM prompts')}
      title={t('admin.screens.prompts.title', 'LLM Prompts')}
      intro={t('admin.screens.prompts.intro',
        'Edit the prompts and tuning the AI Co-pilot uses. Saving creates a new version; you can roll back from each prompt\'s history. Changes apply without a restart.')}
    />
  );
}
