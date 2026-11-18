import { describe, expect, it } from 'vitest';
import { httpUrl } from './links';

describe('httpUrl', () => {
  it('links only http and https references', () => {
    expect(httpUrl('https://docs.example.org/a.pdf')).toBe('https://docs.example.org/a.pdf');
    expect(httpUrl('http://example.org')).toBe('http://example.org/');
    expect(httpUrl('javascript:alert(1)')).toBeUndefined();
    expect(httpUrl('Badge number 4471')).toBeUndefined();
    expect(httpUrl(undefined)).toBeUndefined();
  });
});
