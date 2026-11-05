/** Exact site origins admitted by the helper; production builds configure their own HTTPS origin. */
const STORE_BUILD = process.env.COSMOTUNE_STORE_BUILD === '1';
const configured = process.env.COSMOTUNE_SITE_ORIGINS?.trim() || undefined;
if (STORE_BUILD && !configured?.trim()) throw new Error('Set COSMOTUNE_SITE_ORIGINS for a store build');

export const SITE_ORIGINS: string[] = [...new Set((configured ?? 'http://localhost:5173')
  .split(',').map((value) => value.trim()).filter(Boolean).map((value) => {
    const url = new URL(value);
    const local = url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
    if (url.username || url.password || url.pathname !== '/' || url.search || url.hash ||
        !(url.protocol === 'https:' || (!STORE_BUILD && local))) {
      throw new Error('Helper origins must be exact HTTPS origins (loopback HTTP is development only)');
    }
    return url.origin;
  }))];
if (!SITE_ORIGINS.length) throw new Error('At least one Cosmotune site origin is required');

/** Browser match patterns omit ports; background.ts also verifies the full sender origin. */
export function matchPatternsFor(origins: string[]): string[] {
  return [...new Set(origins.map((origin) => {
    const url = new URL(origin);
    return `${url.protocol}//${url.hostname}/*`;
  }))];
}
