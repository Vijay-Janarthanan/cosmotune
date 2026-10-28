// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';
const music = vi.hoisted(() => ({ pause: vi.fn(), listener: null as null | ((state: { status: string }, old: { status: string }) => void) }));
vi.mock('@/state/player', () => ({ pauseMusicForPodio: music.pause, restoreMusicMediaSession: vi.fn(), usePlayer: { subscribe: (listener: typeof music.listener) => { music.listener = listener; } } }));
vi.mock('@/playback/youtube/engine', () => ({ YouTubeEngine: class { async stop() {} async load() {} } }));
import { pausePodio, playPodio, stopPodio, usePodioLibrary, usePodioPlayer } from './player';
class FakeAudio extends EventTarget {
  paused = true; ended = false; currentTime = 0; duration = 100; src = ''; preload = ''; crossOrigin = '';
  async play() { this.paused = false; this.dispatchEvent(new Event('playing')); }
  pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
  removeAttribute(name: string) { if (name === 'src') this.src = ''; }
  load() {}
}
describe('Podio browser audio lane', () => {
  beforeEach(() => { vi.stubGlobal('Audio', FakeAudio); stopPodio(); usePodioLibrary.setState({ stations: [], shows: [], progress: {}, completed: [] }); music.pause.mockClear(); });
  it('retains podcast identity and yields to music instead of adding episodes to its queue', async () => {
    const episode = { id: 'podio:episode:abc', title: 'Episode', author: 'Show', url: 'https://example.com/episode.mp3' };
    await playPodio(episode);
    expect(music.pause).toHaveBeenCalledOnce(); expect(usePodioPlayer.getState().item?.id).toBe(episode.id);
    expect(usePodioPlayer.getState().playing).toBe(true);
    music.listener?.({ status: 'loading' }, { status: 'paused' });
    expect(usePodioPlayer.getState().item).toBeNull();
  });
  it('saves finite-episode progress and resets Played on replay while excluding live radio', () => {
    const library = usePodioLibrary.getState();
    library.record('podio:episode:one', 95, 100);
    expect(usePodioLibrary.getState().completed).toContain('podio:episode:one');
    library.record('podio:episode:one', 0, 100);
    expect(usePodioLibrary.getState().completed).not.toContain('podio:episode:one');
    library.record('podio:radio:one', 60, 100);
    expect(usePodioLibrary.getState().progress['podio:radio:one']).toBeUndefined();
    pausePodio();
  });
});
