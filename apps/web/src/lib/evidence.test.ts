import { describe, expect, it } from 'vitest';
import { previewKind } from './evidence';

describe('previewKind', () => {
  it('previews the evidence types uploads accept', () => {
    expect(previewKind('image/jpeg')).toBe('image');
    expect(previewKind('image/png')).toBe('image');
    expect(previewKind('application/pdf')).toBe('pdf');
  });

  it('offers no preview for anything else', () => {
    expect(previewKind('image/svg+xml')).toBeUndefined();
    expect(previewKind('text/html')).toBeUndefined();
    expect(previewKind(undefined)).toBeUndefined();
  });
});
