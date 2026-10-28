import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { pauseMusicForPodio, restoreMusicMediaSession, usePlayer } from '@/state/player';
import type { YouTubeEngine } from '@/playback/youtube/engine';
import { publicUrl, type PodioEntry, type PodioShow } from './catalogue';

interface Saved { stations: PodioEntry[]; shows: PodioShow[]; progress: Record<string, number>; completed: string[];
  station: (entry: PodioEntry) => void; follow: (show: PodioShow) => void; record: (id: string, seconds: number, duration: number, ended?: boolean) => void }
export const usePodioLibrary = create<Saved>()(persist((set) => ({
  stations: [], shows: [], progress: {}, completed: [],
  station: (entry) => set((old) => ({ stations: old.stations.some((item) => item.id === entry.id) ? old.stations.filter((item) => item.id !== entry.id) : [...old.stations, entry].slice(-500) })),
  follow: (show) => set((old) => ({ shows: old.shows.some((item) => item.feed === show.feed) ? old.shows.filter((item) => item.feed !== show.feed) : [...old.shows, show].slice(-500) })),
  record: (id, seconds, duration, ended = false) => set((old) => {
    if (!id.startsWith('podio:episode:') || !Number.isFinite(seconds)) return {};
    const finished = ended || duration > 0 && seconds > 0 && seconds >= duration - Math.min(10, duration / 10);
    const progress = Object.fromEntries(Object.entries({ ...old.progress, [id]: Math.max(0, seconds) }).slice(-1000));
    return { progress, completed: (finished ? [...new Set([...old.completed, id])] : old.completed.filter((key) => key !== id)).filter((key) => key in progress) };
  }),
}), { name: 'cosmotune:podio', version: 1 }));

interface Playback { item: PodioEntry | null; playing: boolean; loading: boolean; position: number; duration: number; error: string | null }
export const usePodioPlayer = create<Playback>(() => ({ item: null, playing: false, loading: false, position: 0, duration: 0, error: null }));
let audio: HTMLAudioElement | null = null;
let engine: YouTubeEngine | null = null;
let generation = 0;
let lastSaved = 0;
function save(ended = false) {
  const { item } = usePodioPlayer.getState(); if (!item || item.live || !audio) return;
  usePodioLibrary.getState().record(item.id, audio.currentTime, Number.isFinite(audio.duration) ? audio.duration : item.duration ?? 0, ended);
}
function mediaSession(item: PodioEntry): void {
  if (!('mediaSession' in navigator) || typeof MediaMetadata === 'undefined') return;
  const session = navigator.mediaSession;
  session.metadata = new MediaMetadata({ title: item.title, artist: item.author, album: item.live ? 'Podio · Live radio' : 'Podio · Podcast', artwork: item.artwork && publicUrl(item.artwork) ? [{ src: item.artwork }] : [] });
  const actions: Partial<Record<MediaSessionAction, MediaSessionActionHandler>> = {
    play: () => { if (audio?.paused) void togglePodio(); }, pause: pausePodio, stop: stopPodio,
    seekto: (details) => { if (details.seekTime !== undefined) seekPodio(details.seekTime); },
    seekbackward: (details) => seekPodio((audio?.currentTime ?? 0) - (details.seekOffset ?? 15)),
    seekforward: (details) => seekPodio((audio?.currentTime ?? 0) + (details.seekOffset ?? 15)),
  };
  for (const action of ['play', 'pause', 'stop', 'seekto', 'seekbackward', 'seekforward', 'previoustrack', 'nexttrack'] as const) {
    try { session.setActionHandler(action, actions[action] ?? null); } catch { /* Optional browser action. */ }
  }
}
function element(): HTMLAudioElement {
  if (audio) return audio;
  audio = new Audio(); audio.preload = 'metadata';
  for (const name of ['playing', 'pause', 'waiting', 'loadedmetadata', 'timeupdate', 'ended'] as const) audio.addEventListener(name, () => {
    if (!audio) return;
    usePodioPlayer.setState({ playing: !audio.paused && !audio.ended, loading: name === 'waiting', position: audio.currentTime, duration: Number.isFinite(audio.duration) ? audio.duration : 0 });
    if ('mediaSession' in navigator && usePodioPlayer.getState().item) {
      navigator.mediaSession.playbackState = audio.paused ? 'paused' : 'playing';
      try { if (!usePodioPlayer.getState().item?.live && Number.isFinite(audio.duration) && audio.duration > 0) navigator.mediaSession.setPositionState({ duration: audio.duration, position: Math.min(audio.currentTime, audio.duration), playbackRate: audio.playbackRate }); } catch { /* Optional position API. */ }
    }
    if (name === 'pause' || name === 'ended' || name === 'timeupdate' && Date.now() - lastSaved >= 5000) { save(name === 'ended'); lastSaved = Date.now(); }
  });
  audio.addEventListener('error', () => usePodioPlayer.setState({ loading: false, playing: false, error: 'This public stream could not be played by the browser.' }));
  window.addEventListener('pagehide', () => save());
  return audio;
}
// A podcast never enters the music queue, recommendation model or music history/scrobbling lane.
usePlayer.subscribe((state, old) => { if ((state.status === 'loading' || state.status === 'playing') && state.status !== old.status && usePodioPlayer.getState().item) stopPodio(); });
export async function playPodio(item: PodioEntry): Promise<void> {
  if (!/^podio:(radio|episode):/.test(item.id)) throw new Error('Invalid Podio item identity.');
  const youtube = /^podio:episode:youtube:([A-Za-z0-9_-]{11})$/.exec(item.id)?.[1];
  if (!youtube && !publicUrl(item.url)) throw new Error('This episode has no public HTTPS audio stream.');
  save(); const token = ++generation; const target = element(); target.pause();
  pauseMusicForPodio();
  const library = usePodioLibrary.getState(); const position = item.live || library.completed.includes(item.id) ? 0 : library.progress[item.id] ?? 0;
  usePodioPlayer.setState({ playing: false, loading: true, error: null });
  try {
    if (engine) await engine.stop(); if (token !== generation) return;
    target.removeAttribute('src'); target.load();
    usePodioPlayer.setState({ item, playing: false, loading: true, position, duration: item.duration ?? 0, error: null });
    mediaSession(item);
    if (youtube) {
      target.crossOrigin = 'anonymous';
      engine ??= new (await import('@/playback/youtube/engine')).YouTubeEngine(target);
      if (token !== generation) return;
      await engine.load(youtube, { startSec: position });
    } else {
      target.removeAttribute('crossorigin'); target.src = item.url;
      target.addEventListener('loadedmetadata', () => { if (token === generation && position > 0) target.currentTime = position; }, { once: true });
      await target.play();
    }
    if (token === generation) usePodioPlayer.setState({ loading: false, playing: true });
  } catch (failure) { if (token === generation) usePodioPlayer.setState({ loading: false, playing: false, error: failure instanceof Error ? failure.message : 'Podio playback failed.' }); }
}
export function pausePodio(): void { audio?.pause(); save(); }
export async function togglePodio(): Promise<void> {
  if (!audio || !usePodioPlayer.getState().item) return;
  if (!audio.paused) { pausePodio(); return; }
  pauseMusicForPodio();
  try { await audio.play(); } catch { usePodioPlayer.setState({ error: 'Tap Play again to allow browser audio.' }); }
}
export function seekPodio(position: number): void { if (audio && !usePodioPlayer.getState().item?.live && Number.isFinite(position)) audio.currentTime = Math.max(0, position); }
export function stopPodio(): void { ++generation; pausePodio(); void engine?.stop(); if (audio) { audio.removeAttribute('src'); audio.load(); } usePodioPlayer.setState({ item: null, playing: false, loading: false, error: null }); restoreMusicMediaSession(); }
