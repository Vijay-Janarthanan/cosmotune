import { describe, expect, it } from 'vitest';
import { BRIDGE_CHANNEL, BRIDGE_PROTOCOL, isBridgeRequest } from './protocol';

const valid = { channel: BRIDGE_CHANNEL, protocol: BRIDGE_PROTOCOL, direction: 'to-extension', id: 'request-1', method: 'yt.fetch', params: {} };
describe('page to extension admission', () => {
  it('requires a bounded typed envelope but lets the handler report newer protocol versions', () => {
    expect(isBridgeRequest(valid)).toBe(true);
    expect(isBridgeRequest({ ...valid, protocol: 999 })).toBe(true);
    for (const change of [{ channel: 'other' }, { direction: 'to-page' }, { id: '' }, { id: 'x'.repeat(129) },
      { protocol: '1' }, { method: 'yt.fetch/../../' }, { params: null }, { params: [] }]) {
      expect(isBridgeRequest({ ...valid, ...change })).toBe(false);
    }
  });
  it('rejects oversized or cyclic parameters before dispatch', () => {
    expect(isBridgeRequest({ ...valid, params: { body: 'x'.repeat(2 * 1024 * 1024) } })).toBe(false);
    const cyclic: Record<string, unknown> = {}; cyclic.self = cyclic;
    expect(isBridgeRequest({ ...valid, params: cyclic })).toBe(false);
  });
});
