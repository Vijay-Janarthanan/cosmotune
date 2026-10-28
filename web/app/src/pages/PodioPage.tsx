import { useEffect, useRef, useState } from 'react';
import { PageHeader } from '@/components/PageHeader';
import { feed, radio, shows, youtubeEpisodes, youtubeShows, type PodioEntry, type PodioFeed, type PodioShow } from '@/podio/catalogue';
import { playPodio, seekPodio, usePodioLibrary, usePodioPlayer } from '@/podio/player';

const button = 'rounded-full border border-divider px-5 py-2 hover:bg-quinary disabled:opacity-50';
export default function PodioPage() {
  const [tab, setTab] = useState('Radio'); const [query, setQuery] = useState('');
  const [stations, setStations] = useState<PodioEntry[]>([]); const [podcasts, setPodcasts] = useState<PodioShow[]>([]);
  const [opened, setOpened] = useState<PodioFeed | null>(null); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const request = useRef<AbortController | null>(null); const serial = useRef(0);
  const saved = usePodioLibrary(); const player = usePodioPlayer();
  const run = async (work: (signal: AbortSignal) => Promise<void>) => {
    request.current?.abort(); const controller = new AbortController(); request.current = controller; const token = ++serial.current;
    setBusy(true); setError('');
    try { await work(controller.signal); } catch (failure) { if (token === serial.current && !controller.signal.aborted) setError(failure instanceof Error ? failure.message : 'Podio is unavailable.'); }
    finally { if (token === serial.current) setBusy(false); }
  };
  const search = (term = query) => run(async (signal) => {
    setOpened(null);
    if (tab === 'Radio') { const rows = await radio(term, signal); if (!signal.aborted) setStations(rows); }
    else if (tab === 'YouTube podcasts') { const rows = await youtubeShows(term); if (!signal.aborted) setPodcasts(rows); }
    else { const rows = await shows(term, signal); if (!signal.aborted) setPodcasts(rows); }
  });
  const open = (show: PodioShow) => run(async (signal) => { const next = /^(MPSPPL|MPED)/.test(show.id) ? await youtubeEpisodes(show) : await feed(show.feed, signal); if (!signal.aborted) setOpened(next); });
  useEffect(() => { setQuery(''); setOpened(null); setStations([]); setPodcasts([]); if (tab !== 'Saved') void search(''); return () => { request.current?.abort(); ++serial.current; }; }, [tab]);
  const play = (entry: PodioEntry) => { void playPodio(entry).catch((failure: unknown) => setError(String(failure))); };
  const radioRows = tab === 'Saved' ? saved.stations : tab === 'Radio' ? stations : [];
  const showRows = tab === 'Saved' ? saved.shows : tab === 'Radio' ? [] : podcasts;
  return <><PageHeader title="Podio" /><section className="mx-(--body-gutter) mt-6 space-y-6 pb-12">
    <p className="text-secondary">Radio from around the world and public podcast episodes. No podcast account is needed. Publisher feeds must allow browser access; the Android and desktop apps support additional feeds.</p>
    <div className="flex flex-wrap gap-2" aria-label="Podio sections">{['Radio', 'Podcasts', 'YouTube podcasts', 'Saved'].map((name) => <button key={name} aria-pressed={tab === name} className={`${button} ${tab === name ? 'bg-quinary' : ''}`} onClick={() => setTab(name)}>{name}</button>)}</div>
    {!opened && tab !== 'Saved' && <form className="flex gap-3" onSubmit={(event) => { event.preventDefault(); void search(); }}><input className="min-w-0 flex-1 rounded-xl border border-divider bg-transparent px-4 py-3" aria-label="Search Podio" placeholder={tab === 'Radio' ? 'Search stations' : 'Search podcasts or paste a public RSS feed'} value={query} onChange={(event) => setQuery(event.target.value)} /><button className={button} disabled={busy}>Search</button></form>}
    {busy && <p role="status">Loading…</p>}{error && <p role="alert">{error} If the publisher blocks browser requests, open its feed in the native app.</p>}
    {opened ? <><div className="flex flex-wrap items-center gap-3"><button className={button} onClick={() => { request.current?.abort(); ++serial.current; setBusy(false); setOpened(null); }}>Back</button><h2 className="type-title-2-emphasized flex-1">{opened.show.title}</h2><button className={button} onClick={() => saved.follow(opened.show)}>{saved.shows.some((show) => show.feed === opened.show.feed) ? 'Unfollow' : 'Follow'}</button></div>
      <ul className="divide-y divide-divider">{opened.episodes.map((entry) => <li key={entry.id} className="flex items-center gap-4 py-4"><div className="min-w-0 flex-1"><strong>{entry.title}</strong><p className="text-secondary">{saved.completed.includes(entry.id) ? 'Played' : (saved.progress[entry.id] ?? 0) > 0 ? `Resume at ${Math.floor((saved.progress[entry.id] ?? 0) / 60)} minutes` : entry.author}</p></div><button className={button} onClick={() => play(entry)}>Play</button></li>)}</ul>
      {opened.next && <button className={button} disabled={busy} onClick={() => void run(async (signal) => { const next = await feed(opened.next!, signal, opened.show.feed); if (!signal.aborted) { if (next.show.title !== opened.show.title) throw new Error('The archive opened a different show.'); setOpened({ ...opened, episodes: [...new Map([...opened.episodes, ...next.episodes].map((entry) => [entry.id, entry])).values()], next: next.next === opened.next ? undefined : next.next }); } })}>Load older episodes</button>}
    </> : <ul className="divide-y divide-divider">{radioRows.map((entry) => <li key={entry.id} className="flex items-center gap-4 py-4"><div className="min-w-0 flex-1"><strong>{entry.title}</strong><p className="text-secondary">{entry.author}</p></div><button className={button} onClick={() => saved.station(entry)}>{saved.stations.some((station) => station.id === entry.id) ? 'Unsave' : 'Save'}</button><button className={button} onClick={() => play(entry)}>Listen</button></li>)}{showRows.map((show) => <li key={show.id} className="flex items-center gap-4 py-4"><button className="min-w-0 flex-1 text-left" onClick={() => void open(show)}><strong>{show.title}</strong><p className="text-secondary">{show.author}</p></button><button className={button} onClick={() => saved.follow(show)}>{saved.shows.some((item) => item.feed === show.feed) ? 'Unfollow' : 'Follow'}</button></li>)}</ul>}
    {!busy && !opened && radioRows.length + showRows.length === 0 && <p className="text-secondary">{tab === 'Saved' ? 'Save stations and follow shows to find them here.' : 'No results yet. Try another name.'}</p>}
    {player.item && !player.item.live && player.duration > 0 && <label className="block">Episode position<input type="range" className="mt-3 w-full" min="0" max={player.duration} value={Math.min(player.position, player.duration)} onChange={(event) => seekPodio(Number(event.target.value))} /></label>}
    <p className="type-callout text-secondary">Directory data: Radio Browser and Apple Podcasts. YouTube Music public shows use the existing playback extension. Public availability varies by episode and region.</p>
  </section></>;
}
