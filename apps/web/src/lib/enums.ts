export const REGIONS = [
  'AHAFO',
  'ASHANTI',
  'BONO',
  'BONO_EAST',
  'CENTRAL',
  'EASTERN',
  'GREATER_ACCRA',
  'NORTH_EAST',
  'NORTHERN',
  'OTI',
  'SAVANNAH',
  'UPPER_EAST',
  'UPPER_WEST',
  'VOLTA',
  'WESTERN',
  'WESTERN_NORTH',
] as const;
export const GENDERS = ['MALE', 'FEMALE', 'UNKNOWN'] as const;
export const CASE_STATUSES = ['MISSING', 'FOUND_SAFE', 'FOUND_DECEASED', 'CLOSED'] as const;
export const VERIFICATION_TYPES = ['POLICE', 'NGO', 'SUBJECT_MATTER_EXPERT'] as const;

/** GREATER_ACCRA -> Greater Accra */
export function humanize(value?: string | null) {
  if (!value) return '';
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

export function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleDateString(undefined, { dateStyle: 'medium' }) : '—';
}
