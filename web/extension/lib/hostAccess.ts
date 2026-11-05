export interface SenderAddress { origin?: string; url?: string; id?: string }

function originOfUrl(value: string | undefined): string | null {
  try {
    const url = new URL(value ?? '');
    if (url.origin !== 'null') return url.origin;
    return ['chrome-extension:', 'moz-extension:'].includes(url.protocol) && url.host ? `${url.protocol}//${url.host}` : null;
  } catch { return null; }
}

/** Browser-supplied sender identity, never the caller's request parameters. */
export function allowsSender(sender: SenderAddress, origins: ReadonlySet<string>, ownUrl: string, ownId: string): boolean {
  const ownOrigin = originOfUrl(ownUrl);
  const fromUrl = originOfUrl(sender.url);
  const own = sender.id === ownId && ownOrigin !== null && fromUrl === ownOrigin;
  if (sender.origin === 'null') return own; // An opaque web frame never inherits its URL's trust.
  const origin = sender.origin || fromUrl;
  return !!origin && (origins.has(origin) || (own && origin === ownOrigin));
}
