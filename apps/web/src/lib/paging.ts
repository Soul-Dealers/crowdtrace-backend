/** The page to show once a list has shrunk: the last page that still exists, or 0 when nothing is left. */
export function clampPage(page: number, totalPages: number | undefined): number {
  if (totalPages === undefined) return page;
  return Math.max(0, Math.min(page, totalPages - 1));
}
