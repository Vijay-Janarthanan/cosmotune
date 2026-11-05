/** Metadata only. Media playback uses its own streaming transport. */
export const MAX_METADATA_BYTES = 16 * 1024 * 1024;

export function metadataFetch(url: string | URL, init: RequestInit = {}): Promise<Response> {
  return fetch(url, { ...init, redirect: 'error', signal: init.signal ?? AbortSignal.timeout(15_000) });
}

export async function metadataBytes(response: Response, limit = MAX_METADATA_BYTES): Promise<Uint8Array> {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_METADATA_BYTES) throw new Error('Invalid metadata budget');
  const declared = response.headers.get('content-length');
  if (declared && /^\d+$/.test(declared) && Number(declared) > limit) {
    await response.body?.cancel();
    throw new Error('Metadata response exceeds the size limit');
  }
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > limit) throw new Error('Metadata response exceeds the size limit');
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return bytes;
  } catch (error) {
    await reader.cancel().catch(() => undefined);
    throw error;
  } finally { reader.releaseLock(); }
}

export async function metadataText(response: Response): Promise<string> {
  return new TextDecoder().decode(await metadataBytes(response));
}

export async function metadataJson(response: Response): Promise<unknown> {
  return JSON.parse(await metadataText(response));
}
