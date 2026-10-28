import { afterEach, describe, expect, it, vi } from 'vitest';

const { create } = vi.hoisted(() => ({ create: vi.fn() }));
vi.mock('onnxruntime-web/wasm', () => ({ env: { wasm: {} }, InferenceSession: { create } }));
vi.mock('./modelAssets', () => ({ loadAnalysisAsset: async () => new ArrayBuffer(1) }));
import { trackBeats, vocalCurve } from './models';

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); create.mockReset(); });

describe('optional analysis model recovery', () => {
  it('tries beat analysis again after an unavailable runtime or download', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-28T00:00:00Z'));
    create.mockRejectedValue(new Error('temporary failure'));
    const samples = new Float32Array(1024);
    expect(await trackBeats(samples, 0)).toBeNull();
    expect(await trackBeats(samples, 0)).toBeNull();
    expect(create).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(60_001);
    expect(await trackBeats(samples, 0)).toBeNull();
    expect(create).toHaveBeenCalledTimes(2);
  });

  it('tries vocal analysis again rather than remembering a failed session forever', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-28T00:00:00Z'));
    create.mockRejectedValue(new Error('temporary failure'));
    const samples = new Float32Array(4096);
    expect(await vocalCurve(samples, samples)).toBeNull();
    expect(create).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(60_001);
    expect(await vocalCurve(samples, samples)).toBeNull();
    expect(await vocalCurve(samples, samples)).toBeNull();
    expect(create).toHaveBeenCalledTimes(2);
  });
});
