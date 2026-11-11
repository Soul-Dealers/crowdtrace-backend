/** Ghana emergency numbers; kept out of client modules so server pages can render them too. */
export const EMERGENCY_LINES = [
  { number: '191', label: 'Police' },
  { number: '18555', label: 'Police Toll-Free' },
  { number: '112', label: 'All Emergencies' },
] as const;
