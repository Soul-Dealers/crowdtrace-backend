import type { Config } from 'tailwindcss';

/**
 * Tailwind v4 is configured primarily through CSS (`@import "tailwindcss"` and
 * `@theme` in app/globals.css). This file is kept for tooling/editor awareness
 * and to pin content sources explicitly.
 */
const config: Config = {
  content: ['./src/**/*.{ts,tsx}'],
};

export default config;
