import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => { vi.unstubAllEnvs(); vi.resetModules(); });
async function configured(origins: string, store = false) {
  vi.resetModules(); vi.stubEnv('COSMOTUNE_SITE_ORIGINS', origins); vi.stubEnv('COSMOTUNE_STORE_BUILD', store ? '1' : '0');
  return import('./site.config');
}
describe('helper build origins', () => {
  it('handles an empty CI variable as local development but requires a store deployment origin', async () => {
    expect((await configured('')).SITE_ORIGINS).toEqual(['http://localhost:5173']);
    await expect(configured('', true)).rejects.toThrow('Set COSMOTUNE_SITE_ORIGINS');
  });
  it('normalizes exact HTTPS origins and rejects credential/path/query and production HTTP grants', async () => {
    const result = await configured('https://Music.Example.com/,https://music.example.com', true);
    expect(result.SITE_ORIGINS).toEqual(['https://music.example.com']);
    expect(result.matchPatternsFor(['http://localhost:5173'])).toEqual(['http://localhost/*']);
    for (const origin of ['https://user:secret@example.com', 'https://example.com/path', 'https://example.com?key=x', 'http://localhost:5173']) {
      await expect(configured(origin, true)).rejects.toThrow('exact HTTPS origins');
    }
  });
});
