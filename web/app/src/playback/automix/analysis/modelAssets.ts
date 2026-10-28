/** Optional audio-analysis data, downloaded only when Automix needs it. */
export interface AnalysisAsset {
  url: string;
  bytes: number;
  sha256: string;
}

const BASE = 'https://raw.githubusercontent.com/kushagrasinghx/BitChord/04a4a83889bb8fa5bd6ee6f6097f0c6608671d68/web/app/public/models/';
export const ANALYSIS_ASSETS = {
  beats: {
    url: `${BASE}beat_this_final0_q8.onnx`, bytes: 23_138_816,
    sha256: '7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd',
  },
  vocals: {
    url: `${BASE}vocals_umxhq_int8.onnx`, bytes: 9_054_206,
    sha256: 'a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb',
  },
} satisfies Record<string, AnalysisAsset>;

const CACHE = 'cosmotune-automix-models-v1';

export async function verifyAnalysisAsset(asset: AnalysisAsset, bytes: ArrayBuffer): Promise<boolean> {
  if (bytes.byteLength !== asset.bytes) return false;
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  const actual = [...digest].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  return actual === asset.sha256;
}

/** A corrupt cache entry or changed upstream response can never become executable model data. */
export async function loadVerifiedAnalysisAsset(asset: AnalysisAsset): Promise<ArrayBuffer> {
  const cache = typeof caches !== 'undefined' ? await caches.open(CACHE).catch(() => null) : null;
  const cached = await cache?.match(asset.url).catch(() => undefined);
  if (cached) {
    const bytes = await cached.arrayBuffer();
    if (await verifyAnalysisAsset(asset, bytes)) return bytes;
    await cache?.delete(asset.url);
  }
  const response = await fetch(asset.url, { credentials: 'omit', signal: AbortSignal.timeout(60_000) });
  if (!response.ok) throw new Error(`Audio analysis data unavailable (HTTP ${response.status})`);
  const bytes = await response.arrayBuffer();
  if (!await verifyAnalysisAsset(asset, bytes)) throw new Error('Audio analysis data failed its integrity check');
  // Storage quotas/private browsing can refuse caching; analysis may still use the verified bytes.
  await cache?.put(asset.url, new Response(bytes, { headers: { 'Content-Type': 'application/octet-stream' } })).catch(() => {});
  return bytes;
}

export const loadAnalysisAsset = (name: keyof typeof ANALYSIS_ASSETS) => loadVerifiedAnalysisAsset(ANALYSIS_ASSETS[name]);
