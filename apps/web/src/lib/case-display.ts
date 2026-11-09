/** Display helpers shared by case components. Dates from the API are ISO strings (UTC calendar dates). */

export function daysSince(isoDate: string, now: Date = new Date()): number {
  const then = Date.parse(isoDate.length === 10 ? `${isoDate}T00:00:00Z` : isoDate);
  return Math.max(0, Math.floor((now.getTime() - then) / 86_400_000));
}

export function caseKindLabel(age: number): string {
  return age < 18 ? 'Missing child' : 'Missing person';
}

export function formatShortDate(isoDate: string): string {
  return new Date(isoDate.length === 10 ? `${isoDate}T00:00:00Z` : isoDate)
    .toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
}

export function humanRegion(region: string): string {
  return region.toLowerCase().split('_').map((w) => w[0].toUpperCase() + w.slice(1)).join(' ');
}

/** Ghana numbers read best in 3-3-4 groups: 024 561 7720. */
export function formatPhone(phone: string): string {
  const digits = phone.replace(/\D/g, '');
  const local = digits.startsWith('233') ? `0${digits.slice(3)}` : digits;
  return local.length === 10 ? `${local.slice(0, 3)} ${local.slice(3, 6)} ${local.slice(6)}` : phone;
}
