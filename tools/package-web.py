#!/usr/bin/env python3
"""Verify the browser companions and package the static site with source/licence pointers."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    subprocess.check_call([sys.executable, str(root / 'tools/verify-companion-imports.py')])
    web = root / 'web'
    dist = web / 'app/dist'
    published = json.loads((dist / 'downloads/extension.json').read_text(encoding='utf-8'))
    assert published['version'] == args.version, 'Published helper version differs from release'
    assert (dist / 'index.html').is_file(), 'No production browser app'
    public_key = (web / 'extension/dev-key/public-key.txt').read_text(encoding='utf-8').strip()
    helpers = []
    for browser in ('chrome', 'firefox'):
        name = published['files'][browser]
        assert Path(name).name == name and name.endswith('.zip'), 'Invalid helper download filename'
        archive = dist / 'downloads' / name
        with zipfile.ZipFile(archive) as bundle:
            manifests = [path for path in bundle.namelist() if path.endswith('/manifest.json')]
            assert len(manifests) == 1, 'Expected one helper manifest'
            manifest = json.loads(bundle.read(manifests[0]))
            assert manifest['version'] == args.version and manifest['name'] == 'Cosmotune'
            assert set(manifest['permissions']) == {'cookies', 'storage', 'declarativeNetRequest'}
            hosts = manifest['host_permissions'] + manifest.get('optional_host_permissions', [])
            assert all('discord' not in host and host != '<all_urls>' for host in hosts), 'Unexpected helper access'
            if browser == 'chrome':
                assert manifest['key'] == public_key, 'Chromium development identity changed'
                assert manifest['externally_connectable']['matches'], 'No admitted player origin'
            else:
                assert manifest['browser_specific_settings']['gecko']['id'] == 'cosmotune-helper@cosmotune.invalid'
            assert not any('/discord.' in path for path in bundle.namelist()), 'Disabled integration was packaged'
        helpers.append((browser, archive))

    # Include licence texts shipped with every installed package, including build tools.
    # This deliberately over-includes rather than guessing which transitive code Rolldown inlined.
    modules = web / 'node_modules/.pnpm'
    packages = list(modules.glob('*/node_modules/*/package.json')) + list(modules.glob('*/node_modules/@*/*/package.json'))
    notices = ['Cosmotune browser companion third-party notices',
               'Source: https://github.com/Vijay-Janarthanan/cosmotune',
               'Native project: GNU GPLv3; browser Automix includes AGPLv3 code. See LICENSE, NOTICE.md and licenses/automix-web/.', '']
    seen = set()
    for metadata in sorted(packages):
        info = json.loads(metadata.read_text(encoding='utf-8'))
        key = (info.get('name'), info.get('version'))
        if not all(key) or key in seen:
            continue
        seen.add(key)
        notices += ['=' * 72, f'{key[0]} {key[1]}', 'Licence: ' + str(info.get('license', info.get('licenses', 'See package source')))]
        repository = info.get('repository')
        if repository:
            notices.append('Repository: ' + str(repository))
        for path in sorted(metadata.parent.iterdir()):
            if path.is_file() and path.name.lower().startswith(('license', 'licence', 'copying', 'notice')):
                notices += ['', path.name, path.read_text(encoding='utf-8', errors='replace')]
        notices.append('')
    assert len(seen) >= 30, 'Dependencies/licences are missing; run the locked pnpm install first'
    notice_text = '\n'.join(notices)
    (dist / 'THIRD_PARTY_NOTICES.txt').write_text(notice_text, encoding='utf-8')
    # Both helper archives are unsigned/unpacked builds. Put notices inside their single folder.
    for _, original in helpers:
        rewritten = io.BytesIO()
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(rewritten, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as destination:
            prefix = next(path.rsplit('/', 1)[0] for path in source.namelist() if path.endswith('/manifest.json'))
            extras = {f'{prefix}/THIRD_PARTY_NOTICES.txt': notice_text,
                      f'{prefix}/LICENSE': (root / 'LICENSE').read_text(encoding='utf-8'),
                      f'{prefix}/NOTICE.md': (root / 'NOTICE.md').read_text(encoding='utf-8')}
            for name in source.namelist():
                if name not in extras:
                    destination.writestr(name, source.read(name))
            for name, content in extras.items():
                destination.writestr(name, content)
        original.write_bytes(rewritten.getvalue())
    args.output.mkdir(parents=True, exist_ok=True)
    site = args.output / f'Cosmotune-{args.version}-web.zip'
    with zipfile.ZipFile(site, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for path in sorted(dist.rglob('*')):
            if path.is_file():
                assert not path.is_symlink(), 'Unexpected symbolic link in site output'
                bundle.write(path, path.relative_to(dist).as_posix())
        bundle.write(web / 'README.md', 'DEPLOYMENT.md')
        bundle.write(root / 'LICENSE', 'LICENSE')
        bundle.write(root / 'NOTICE.md', 'NOTICE.md')
        bundle.write(root / 'FORK.md', 'FORK.md')
        bundle.write(web / 'UPSTREAM_IMPORT.json', 'SOURCE_PROVENANCE.json')
        if (web / 'UPSTREAM_DELTAS.json').is_file():
            bundle.write(web / 'UPSTREAM_DELTAS.json', 'web/UPSTREAM_DELTAS.json')
        if (web / 'docs/automix.md').is_file():
            bundle.write(web / 'docs/automix.md', 'web/docs/automix.md')
            bundle.write(web / 'docs/automix.md', 'docs/automix.md')
        # The optional engine and downloadable weights have notices beyond npm's package metadata.
        for path in sorted((root / 'licenses/automix-web').rglob('*')):
            if path.is_file():
                assert not path.is_symlink(), 'Unexpected symbolic link in audio analysis notices'
                bundle.write(path, path.relative_to(root).as_posix())
    for browser, original in helpers:
        output = args.output / f'Cosmotune-{args.version}-helper-{browser}.zip'
        # This copy matches the site's download, including the retained licence notices.
        output.write_bytes(original.read_bytes())
    (args.output / 'Cosmotune-web-THIRD_PARTY_NOTICES.txt').write_text(notice_text, encoding='utf-8')
    for path in [site, *(args.output / f'Cosmotune-{args.version}-helper-{browser}.zip' for browser, _ in helpers)]:
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        print(f'OK {path.name}: {path.stat().st_size:,} bytes; SHA256 {digest}')
    print(f'Included licence metadata/texts for {len(seen)} installed packages')


if __name__ == '__main__':
    main()
