import { describe, it, expect } from 'vitest';
import { modelFamily, isLocalProvider, modelBadgeTooltip } from './modelFamily';

describe('modelFamily', () => {
  it('names the family from the model, not the provider route', () => {
    // The two historical misnomers this helper exists for.
    expect(modelFamily('gemini-3.6-flash', 'gemma')).toBe('Gemini');
    expect(modelFamily('qwen2.5:1.5b', 'inhouse-hi')).toBe('Qwen');
  });

  it('still resolves the routes whose id and model agree', () => {
    expect(modelFamily('gemma3:4b', 'inhouse')).toBe('Gemma');
    expect(modelFamily('gemma-3-27b-it', 'gemma')).toBe('Gemma');
    expect(modelFamily('granite3.1-dense:2b', 'inhouse-granite')).toBe('Granite');
    expect(modelFamily('phi3.5', 'inhouse-phi')).toBe('Phi');
    expect(modelFamily('claude-opus-4-7', 'claude')).toBe('Claude');
  });

  it('tests gemini before gemma so a gemini model is never labelled Gemma', () => {
    expect(modelFamily('gemini-2.0-flash', 'gemma')).toBe('Gemini');
    expect(modelFamily('gemini-1.5-pro', 'gemma')).toBe('Gemini');
  });

  it('derives a label for a model it has no rule for', () => {
    expect(modelFamily('falcon3:7b', 'inhouse')).toBe('Falcon');
  });

  it('falls back to the provider id when no model was recorded', () => {
    expect(modelFamily('', 'gemma')).toBe('gemma');
    expect(modelFamily(null, 'inhouse')).toBe('inhouse');
    expect(modelFamily(null, null)).toBe('—');
  });
});

describe('isLocalProvider', () => {
  it('treats every inhouse route as on-premise', () => {
    expect(isLocalProvider('inhouse')).toBe(true);
    expect(isLocalProvider('inhouse-hi')).toBe(true);
    expect(isLocalProvider('gemma')).toBe(false);
    expect(isLocalProvider('claude')).toBe(false);
    expect(isLocalProvider(null)).toBe(false);
  });
});

describe('modelBadgeTooltip', () => {
  it('keeps the exact model and the routing id discoverable', () => {
    const tip = modelBadgeTooltip('gemini-3.6-flash', 'gemma');
    expect(tip).toContain('gemini-3.6-flash');
    expect(tip).toContain('gemma');
    expect(tip).toContain('external API');
    expect(modelBadgeTooltip('qwen2.5:1.5b', 'inhouse-hi')).toContain('on-premise');
  });

  it('says so when there is nothing to show', () => {
    expect(modelBadgeTooltip(null, null)).toBe('No model recorded');
  });
});
