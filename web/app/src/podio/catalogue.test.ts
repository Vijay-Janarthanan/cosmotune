// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest';
import { parseFeed, publicUrl } from './catalogue';

describe('public Podio feeds', () => {
  it('keeps the native Podio GUID identity through archive pages and a UTF-8 BOM', async () => {
    const xml = '\uFEFF<rss><channel><title>Public Show</title><item><guid>one</guid><title>First</title><enclosure url="audio/one.mp3"/><duration>1:25</duration></item><item><title>No stream</title></item><link rel="next" href="older.xml"/></channel></rss>';
    const first = await parseFeed(xml, 'https://example.com/feed.xml');
    expect(first.episodes).toHaveLength(1);
    expect(first.episodes[0]?.url).toBe('https://example.com/audio/one.mp3');
    expect(first.episodes[0]?.id).toMatch(/^podio:episode:[a-f0-9]{24}$/);
    expect(first.episodes[0]?.duration).toBe(85);
    expect(first.next).toBe('https://example.com/older.xml');
    const older = await parseFeed(xml, 'https://example.com/older.xml', first.show.feed);
    expect(older.episodes[0]?.id).toBe(first.episodes[0]?.id);
  });
  it('rejects external entities and unsafe destinations rather than manufacturing audio links', async () => {
    await expect(parseFeed('<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///private">]><rss/>', 'https://example.com/feed')).rejects.toThrow();
    for (const url of ['', 'http://example.com/audio', 'file:///a', 'https://user:password@example.com/a', 'https://127.0.0.1/a', 'https://service.local/a']) expect(publicUrl(url, 'https://example.com/feed')).toBeNull();
    const noAudio = await parseFeed('<rss><channel><title>Show</title><item><title>Missing</title></item></channel></rss>', 'https://example.com/feed');
    expect(noAudio.episodes).toHaveLength(0);
  });
});
