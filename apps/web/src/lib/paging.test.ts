import { describe, expect, it } from 'vitest';
import { clampPage } from './paging';

describe('clampPage', () => {
  it('moves back to the last page when the current one no longer exists', () => {
    expect(clampPage(1, 1)).toBe(0);
    expect(clampPage(4, 2)).toBe(1);
  });

  it('keeps a page that still exists, and page 0 when nothing is left', () => {
    expect(clampPage(1, 3)).toBe(1);
    expect(clampPage(0, 0)).toBe(0);
    expect(clampPage(2, 0)).toBe(0);
    expect(clampPage(2, undefined)).toBe(2);
  });
});
