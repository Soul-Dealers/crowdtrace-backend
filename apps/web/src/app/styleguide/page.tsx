import { notFound } from 'next/navigation';
import { StyleguideContent } from './content';

/** Dev-only gallery of the CrowdTrace components with sample data. */
export default function StyleguidePage() {
  if (process.env.NODE_ENV === 'production') notFound();
  return <StyleguideContent />;
}
