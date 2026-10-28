// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
vi.mock('@/state/player', () => ({ pauseMusicForPodio: vi.fn(), restoreMusicMediaSession: vi.fn(), usePlayer: { subscribe: vi.fn() } }));
vi.mock('@/podio/catalogue', async (original) => ({ ...(await original<object>()),
  radio: async () => [{ id: 'podio:radio:fixture', title: 'Fixture FM', author: 'India', url: 'https://example.com/radio.mp3', live: true }],
  shows: async () => [{ id: 'show', title: 'Fixture Show', author: 'Publisher', feed: 'https://example.com/feed.xml' }],
  feed: async () => ({ show: { id: 'show', title: 'Fixture Show', author: 'Publisher', feed: 'https://example.com/feed.xml' }, episodes: [{ id: 'podio:episode:fixture', title: 'First episode', author: 'Fixture Show', url: 'https://example.com/episode.mp3' }] }),
}));
import PodioPage from './PodioPage';
import { PodioBar } from '@/podio/PodioBar';
import { stopPodio, usePodioLibrary, usePodioPlayer } from '@/podio/player';

class FakeAudio extends EventTarget {
  paused = true; ended = false; currentTime = 0; duration = 100; src = ''; preload = ''; crossOrigin = '';
  async play() { this.paused = false; this.dispatchEvent(new Event('playing')); }
  pause() { this.paused = true; this.dispatchEvent(new Event('pause')); }
  removeAttribute() {} load() {}
}
(globalThis as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;
vi.stubGlobal('Audio', FakeAudio);
afterEach(() => { act(() => stopPodio()); });
describe('browser Podio page', () => {
  it('browses stations, saves favorites, opens publisher episodes and keeps usable playback controls', async () => {
    const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
    usePodioLibrary.setState({ stations: [], shows: [], progress: {}, completed: [] });
    const click = async (label: string) => { const button = [...host.querySelectorAll('button')].find((node) => node.textContent === label); expect(button).toBeTruthy(); await act(async () => { button!.click(); }); };
    try {
      await act(async () => { root.render(<MemoryRouter><PodioPage /><PodioBar /></MemoryRouter>); });
      expect(host.textContent).toContain('Fixture FM'); await click('Save');
      expect(usePodioLibrary.getState().stations[0]?.id).toBe('podio:radio:fixture');
      await click('Podcasts'); expect(host.textContent).toContain('Fixture Show');
      const show = [...host.querySelectorAll('button')].find((node) => node.textContent?.includes('Fixture Show'))!;
      await act(async () => { show.click(); }); expect(host.textContent).toContain('First episode');
      await click('Play'); expect(usePodioPlayer.getState().item?.id).toBe('podio:episode:fixture');
      expect(host.querySelector('[aria-label="Podio player"]')).toBeTruthy();
      await click('Radio'); expect(usePodioPlayer.getState().playing).toBe(true);
      await act(async () => { (host.querySelector('[aria-label="Close Podio player"]') as HTMLButtonElement).click(); });
      expect(usePodioPlayer.getState().item).toBeNull();
    } finally { act(() => root.unmount()); host.remove(); }
  });
});
