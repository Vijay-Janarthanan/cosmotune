import { getInnertube } from '@/playback/youtube/innertube';

export interface PodioEntry { id: string; title: string; author: string; url: string; artwork?: string; live?: boolean; duration?: number }
export interface PodioShow { id: string; title: string; author: string; feed: string; artwork?: string }
export interface PodioFeed { show: PodioShow; episodes: PodioEntry[]; next?: string }
type Raw = Record<string, unknown>;
const record = (value: unknown): Raw => value && typeof value === 'object' && !Array.isArray(value) ? value as Raw : {};
export function publicUrl(value: string, base?: string): string | null {
  try {
    if (!value.trim()) return null;
    const url = new URL(value, base);
    if (url.protocol !== 'https:' || url.username || url.password || !url.hostname.includes('.') || /^[0-9.]+$/.test(url.hostname) || /^(localhost|\[)/i.test(url.hostname) || /\.(local|internal)$/i.test(url.hostname)) return null;
    return url.href;
  } catch { return null; }
}
async function identity(value: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))].slice(0, 12).map((b) => b.toString(16).padStart(2, '0')).join('');
}
async function read(url: string, signal?: AbortSignal): Promise<string> {
  const address = publicUrl(url); if (!address) throw new Error('Use a public HTTPS feed or directory.');
  const response = await fetch(address, { credentials: 'omit', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : AbortSignal.timeout(15_000) });
  if (!response.ok) throw new Error('The public directory or feed is unavailable.');
  const reader = response.body?.getReader(); if (!reader) throw new Error('The feed is empty.');
  const chunks: Uint8Array[] = []; let length = 0;
  try { while (true) { const { done, value } = await reader.read(); if (done) break; length += value.length; if (length > 8 * 1024 * 1024) throw new Error('The feed exceeds 8 MB.'); chunks.push(value); } }
  finally { await reader.cancel().catch(() => {}); }
  const bytes = new Uint8Array(length); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return new TextDecoder().decode(bytes);
}
export async function radio(query: string, signal?: AbortSignal): Promise<PodioEntry[]> {
  let error: unknown;
  for (const host of ['de1.api.radio-browser.info', 'de2.api.radio-browser.info']) try {
    const url = new URL(`https://${host}/json/stations/search`);
    Object.entries({ name: query.trim().slice(0, 200), hidebroken: 'true', order: 'clickcount', reverse: 'true', limit: '300' }).forEach(([k, v]) => url.searchParams.set(k, v));
    const rows = JSON.parse(await read(url.href, signal)) as Raw[];
    return rows.flatMap((row) => { const stream = publicUrl(String(row.url_resolved || row.url || '')); const id = String(row.stationuuid || ''); return stream && /^[A-Za-z0-9-]{1,64}$/.test(id) ? [{ id: `podio:radio:${id}`, title: String(row.name), author: [row.country, row.language].filter(Boolean).join(' · '), url: stream, artwork: publicUrl(String(row.favicon || '')) ?? undefined, live: true }] : []; });
  } catch (failure) { if (signal?.aborted) throw failure; error = failure; }
  throw error;
}
export async function shows(query: string, signal?: AbortSignal): Promise<PodioShow[]> {
  if (publicUrl(query.trim())) return [(await feed(query.trim(), signal)).show];
  const url = new URL('https://itunes.apple.com/search');
  Object.entries({ term: query.trim() || 'podcast', media: 'podcast', entity: 'podcast', country: 'IN', limit: '40' }).forEach(([k, v]) => url.searchParams.set(k, v));
  const root = JSON.parse(await read(url.href, signal)) as { results?: Raw[] };
  return Promise.all((root.results ?? []).flatMap((row) => { const address = publicUrl(String(row.feedUrl || '')); return address ? [(async () => ({ id: await identity(address), title: String(row.collectionName), author: String(row.artistName), feed: address, artwork: publicUrl(String(row.artworkUrl600 || '')) ?? undefined }))()] : []; }));
}
export async function parseFeed(xml: string, address: string, rootAddress = address): Promise<PodioFeed> {
  if (xml.length > 8 * 1024 * 1024 || /<!DOCTYPE|<!ENTITY/i.test(xml)) throw new Error('Unsafe or oversized podcast XML.');
  const document = new DOMParser().parseFromString(xml.replace(/^\uFEFF/, ''), 'application/xml');
  if (document.querySelector('parsererror')) throw new Error('Invalid podcast XML.');
  const channel = document.querySelector('channel') ?? document.documentElement;
  const child = (element: Element, name: string) => [...element.children].find((node) => node.localName === name);
  const field = (element: Element, name: string) => child(element, name)?.textContent?.trim() ?? '';
  const title = field(channel, 'title'); if (!title) throw new Error('The feed has no podcast title.');
  const image = child(channel, 'image'); const artwork = publicUrl(image?.getAttribute('href') || (image && field(image, 'url')) || '', address) ?? undefined;
  const show: PodioShow = { id: await identity(rootAddress), title, author: field(channel, 'author'), feed: rootAddress, artwork };
  const entries = [...channel.children].filter((node) => node.localName === 'item' || node.localName === 'entry').slice(0, 1000);
  const episodes: PodioEntry[] = [];
  for (const entry of entries) {
    const enclosure = [...entry.children].find((node) => node.localName === 'enclosure' || node.localName === 'link' && node.getAttribute('rel') === 'enclosure');
    const url = publicUrl(enclosure?.getAttribute('url') || enclosure?.getAttribute('href') || '', address); const name = field(entry, 'title');
    if (!url || !name) continue;
    const guid = field(entry, 'guid') || field(entry, 'id') || url;
    const pieces = field(entry, 'duration').split(':').map(Number); const duration = pieces.every(Number.isFinite) ? pieces.reduce((sum, part) => sum * 60 + part, 0) : 0;
    episodes.push({ id: `podio:episode:${await identity(`${rootAddress}|${guid}`)}`, title: name, author: title, url, artwork, duration });
  }
  const next = [...channel.children].find((node) => node.localName === 'link' && node.getAttribute('rel') === 'next')?.getAttribute('href');
  return { show, episodes: [...new Map(episodes.map((episode) => [episode.id, episode])).values()], next: next ? publicUrl(next, address) ?? undefined : undefined };
}
export async function feed(address: string, signal?: AbortSignal, rootAddress = address): Promise<PodioFeed> {
  return parseFeed(await read(address, signal), address, rootAddress);
}
function collect(root: unknown, key: string, output: Raw[] = [], depth = 0): Raw[] {
  if (depth > 35 || output.length >= 1000) return output;
  if (Array.isArray(root)) root.forEach((item) => collect(item, key, output, depth + 1));
  else for (const [name, value] of Object.entries(record(root))) { if (name === key) output.push(record(value)); else collect(value, key, output, depth + 1); }
  return output;
}
function text(value: unknown): string { const raw = record(value); return typeof raw.simpleText === 'string' ? raw.simpleText : Array.isArray(raw.runs) ? raw.runs.map((run) => String(record(run).text ?? '')).join('') : ''; }
export async function youtubeShows(query: string): Promise<PodioShow[]> {
  const yt = await getInnertube();
  const response = await yt.actions.execute(query.trim() ? '/search' : '/browse', { ...(query.trim() ? { query: query.trim().slice(0, 200) } : { browseId: 'FEmusic_podcasts' }), client: 'YTMUSIC' });
  const rows = [...collect(response.data, 'musicTwoRowItemRenderer'), ...collect(response.data, 'musicResponsiveListItemRenderer')];
  const result: PodioShow[] = [];
  for (const row of rows) {
    const id = String(record(record(row.navigationEndpoint).browseEndpoint).browseId ?? '');
    if (!/^(MPSPPL|MPED)[A-Za-z0-9_-]{4,200}$/.test(id)) continue;
    const title = text(row.title) || text(record(record((row.flexColumns as unknown[] | undefined)?.[0]).musicResponsiveListItemFlexColumnRenderer).text);
    if (title) result.push({ id, title, author: text(row.subtitle), feed: `https://music.youtube.com/browse/${id}` });
  }
  return [...new Map(result.map((show) => [show.id, show])).values()];
}
export async function youtubeEpisodes(show: PodioShow): Promise<PodioFeed> {
  if (!/^(MPSPPL|MPED)[A-Za-z0-9_-]{4,200}$/.test(show.id)) throw new Error('Choose a YouTube Music podcast show.');
  const yt = await getInnertube(); const response = await yt.actions.execute('/browse', { browseId: show.id, client: 'YTMUSIC' });
  const rows = [...collect(response.data, 'musicMultiRowListItemRenderer'), ...collect(response.data, 'musicResponsiveListItemRenderer')];
  const episodes: PodioEntry[] = [];
  for (const row of rows) {
    const endpoint = record(row.onTap ?? row.navigationEndpoint); const id = String(record(endpoint.watchEndpoint).videoId ?? record(row.playlistItemData).videoId ?? '');
    const title = text(row.title) || text(record(record((row.flexColumns as unknown[] | undefined)?.[0]).musicResponsiveListItemFlexColumnRenderer).text);
    if (/^[A-Za-z0-9_-]{11}$/.test(id) && title) episodes.push({ id: `podio:episode:youtube:${id}`, title, author: show.title, url: '' });
  }
  return { show, episodes: [...new Map(episodes.map((episode) => [episode.id, episode])).values()] };
}
