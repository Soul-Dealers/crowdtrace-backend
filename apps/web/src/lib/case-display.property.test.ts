import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { daysSince, formatPhone, humanRegion, timeAgo } from './case-display';

const digits = (n: number) => fc.array(fc.integer({ min: 0, max: 9 }), { minLength: n, maxLength: n }).map((d) => d.join(''));

describe('case display helpers', () => {
  it('formatPhone shows any Ghana number, local or +233, as 0XX XXX XXXX', () => {
    fc.assert(fc.property(digits(9), fc.constantFrom('0', '+233', '233', '+233 '), (rest, prefix) => {
      expect(formatPhone(prefix + rest)).toBe(`0${rest.slice(0, 2)} ${rest.slice(2, 5)} ${rest.slice(5)}`);
    }));
  });

  it('formatPhone is idempotent and never loses digits it does not reformat', () => {
    fc.assert(fc.property(fc.string(), (phone) => {
      const once = formatPhone(phone);
      expect(formatPhone(once)).toBe(once);
      if (once !== phone) expect(once.replace(/\D/g, '').slice(1)).toBe(phone.replace(/\D/g, '').replace(/^(233|0)/, ''));
    }));
  });

  it('humanRegion round-trips every enum-shaped region', () => {
    const word = fc.stringMatching(/^[A-Z]{1,10}$/);
    fc.assert(fc.property(fc.array(word, { minLength: 1, maxLength: 4 }), (words) => {
      const region = words.join('_');
      expect(humanRegion(region).toUpperCase().replaceAll(' ', '_')).toBe(region);
    }));
  });

  const instant = fc.date({ min: new Date('2000-01-01'), max: new Date('2040-01-01'), noInvalidDate: true });

  it('daysSince is never negative and never decreases as time passes', () => {
    fc.assert(fc.property(instant, instant, fc.nat(10_000), (then, now, later) => {
      const iso = then.toISOString().slice(0, 10);
      const today = daysSince(iso, now);
      expect(today).toBeGreaterThanOrEqual(0);
      expect(daysSince(iso, new Date(now.getTime() + later * 3_600_000))).toBeGreaterThanOrEqual(today);
    }));
  });

  it('timeAgo reads a zoneless API timestamp as UTC', () => {
    fc.assert(fc.property(instant, instant, (then, now) => {
      const utc = then.toISOString();
      expect(timeAgo(utc.slice(0, -1), now)).toBe(timeAgo(utc, now));
    }));
  });
});
