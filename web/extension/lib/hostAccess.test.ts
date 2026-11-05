import { describe, expect, it } from 'vitest';
import { allowsSender } from './hostAccess';

describe('helper sender admission', () => {
  const origins = new Set(['http://localhost:5173', 'https://music.example.com']);
  const own = 'chrome-extension://our-helper/popup.html';
  it('checks exact site origins including ports and rejects lookalike hosts and opaque frames', () => {
    expect(allowsSender({ origin: 'http://localhost:5173' }, origins, own, 'our-helper')).toBe(true);
    for (const origin of ['http://localhost:5174', 'https://music.example.com.evil.invalid', 'https://other.example.com', 'null']) {
      expect(allowsSender({ origin, url: 'https://music.example.com' }, origins, own, 'our-helper')).toBe(false);
    }
  });
  it('admits only this extension for its own Chromium and Firefox pages', () => {
    expect(allowsSender({ id: 'our-helper', url: own }, origins, own, 'our-helper')).toBe(true);
    expect(allowsSender({ id: 'different-helper', url: own }, origins, own, 'our-helper')).toBe(false);
    const firefox = 'moz-extension://session-uuid/grant.html';
    expect(allowsSender({ id: 'our-helper', origin: 'null', url: firefox }, origins, firefox, 'our-helper')).toBe(true);
    expect(allowsSender({ id: 'our-helper', origin: 'null', url: 'https://music.example.com' }, origins, firefox, 'our-helper')).toBe(false);
  });
});
