#!/usr/bin/env python3
"""Validate companion source indexes against the central attribution ledger."""
import json
from pathlib import Path
import re


def main():
    root = Path(__file__).resolve().parents[1]
    ledger = (root / 'FORK.md').read_text(encoding='utf-8')
    records = {}
    for line in ledger.splitlines():
        match = re.match(r'^\| ((?:TV|WEB)-(?:X-)?\d{3,4}) \| ', line)
        if match:
            assert match[1] not in records, f'Duplicate source record: {match[1]}'
            records[match[1]] = line
    seen = set()
    for module in ('tv', 'web'):
        manifest = json.loads((root / module / 'UPSTREAM_IMPORT.json').read_text(encoding='utf-8'))
        assert re.fullmatch(r'[0-9a-f]{40}', manifest['revision']), f'No pinned revision: {module}'
        targets = set()
        for entry in manifest['files'] + manifest.get('excluded', []):
            key = entry['sourceRecord']
            assert key not in seen, f'Reused source record: {key}'
            seen.add(key)
            row = records[key]
            digest = entry.get('sourceSha256', entry.get('sha256'))
            assert re.fullmatch(r'[0-9a-f]{64}', digest), f'Invalid original SHA256: {key}'
            assert digest in row and manifest['revision'] in row, f'Changed source attribution: {key}'
            assert 'https://github.com/' in row, f'Missing original source link: {key}'
            if 'target' in entry:
                target = Path(entry['target'])
                assert target.as_posix().startswith(module + '/') and '..' not in target.parts and not target.is_absolute()
                assert target not in targets and (root / target).is_file(), f'Missing/duplicate imported target: {key}'
                targets.add(target)
                assert entry['target'] in row, f'Ledger target differs: {key}'
                if module == 'tv' and target.suffix == '.kt':
                    assert 'FORK.md companion source record ' + key in (root / target).read_text(encoding='utf-8'), f'No source marker: {key}'
        print(f'OK {module}: {len(targets)} imported targets, pinned source hashes and ledger links')
    assert seen == set(records), 'Orphaned companion source record in the ledger'
    delta_path = root / 'web' / 'UPSTREAM_DELTAS.json'
    if delta_path.is_file():
        delta = json.loads(delta_path.read_text(encoding='utf-8'))
        revision = delta['revision']
        assert re.fullmatch(r'[0-9a-f]{40}', revision), 'No pinned browser delta revision'
        delta_records = {}
        for line in ledger.splitlines():
            match = re.match(r'^\| (WDX-\d{4}) ', line)
            if match:
                assert match[1] not in delta_records, f'Duplicate delta source record: {match[1]}'
                delta_records[match[1]] = line
        targets = set()
        keys = set()
        for entry in delta['files']:
            key = entry['sourceRecord']
            assert key not in keys, f'Reused delta source record: {key}'
            keys.add(key)
            row = delta_records[key]
            digest = entry['sourceSha256']
            assert re.fullmatch(r'[0-9a-f]{64}', digest), f'Invalid delta SHA256: {key}'
            assert digest in row and revision in row and 'https://github.com/' in row, f'Changed delta attribution: {key}'
            target = Path(entry['target'])
            assert target.as_posix().startswith('web/') and '..' not in target.parts and not target.is_absolute()
            assert target not in targets and (root / target).is_file(), f'Missing/duplicate delta target: {key}'
            assert entry['target'] in row and entry['source'] in row, f'Delta source or target differs: {key}'
            targets.add(target)
        assert keys == set(delta_records), 'Orphaned browser delta source record'
        print(f'OK browser deltas: {len(targets)} reviewed targets, pinned source hashes and ledger links')


if __name__ == '__main__':
    main()
