import { PrefetchLink } from '@/components/PrefetchLink';
import { stopPodio, togglePodio, usePodioPlayer } from './player';
export function PodioBar() {
  const player = usePodioPlayer(); if (!player.item) return null;
  return <div className="glass-material absolute bottom-5 left-[calc(var(--web-navigation-width)+20px)] right-5 z-40 flex items-center gap-4 rounded-2xl border border-divider p-4 shadow-xl max-sm:left-4" role="region" aria-label="Podio player">
    <PrefetchLink to="/podio" className="min-w-0 flex-1"><strong className="block truncate">{player.item.title}</strong><span className="type-callout text-secondary">{player.error || (player.loading ? 'Loading…' : player.item.live ? 'Podio · Live radio' : 'Podio · Podcast')}</span></PrefetchLink>
    <button className="rounded-full bg-quinary px-4 py-2" onClick={() => void togglePodio()}>{player.playing ? 'Pause' : 'Play'}</button>
    <button className="rounded-full px-3 py-2" onClick={stopPodio} aria-label="Close Podio player">×</button>
  </div>;
}
