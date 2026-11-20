/** Exact matches only: an SVG or HTML file must never be rendered in the page. */
const KINDS: Record<string, 'image' | 'pdf'> = {
  'image/jpeg': 'image',
  'image/png': 'image',
  'application/pdf': 'pdf',
};

export function previewKind(contentType: string | undefined): 'image' | 'pdf' | undefined {
  return contentType ? KINDS[contentType] : undefined;
}
