#!/usr/bin/env python3
"""Check signed TV APK identities, entry points, ABIs and phone separation."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import zipfile


def command(*args):
    return subprocess.check_output([str(arg) for arg in args], stderr=subprocess.STDOUT, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-dir', type=Path, required=True)
    parser.add_argument('--phone-apk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path)
    args = parser.parse_args()
    sdk = args.sdk or Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    tools = sorted((sdk / 'build-tools').glob('*'), reverse=True)
    aapt = next((p / ('aapt.exe' if os.name == 'nt' else 'aapt') for p in tools
                 if (p / ('aapt.exe' if os.name == 'nt' else 'aapt')).is_file()), None)
    signer = next((p / 'lib/apksigner.jar' for p in tools if (p / 'lib/apksigner.jar').is_file()), None)
    if aapt is None or signer is None:
        raise SystemExit('Android build-tools (aapt/apksigner) are required; supply --sdk')
    java_home = os.environ.get('JAVA_HOME')
    java = Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if java_home else 'java'

    def identity(apk):
        info = command(aapt, 'dump', 'badging', apk)
        match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", info)
        if not match:
            raise AssertionError(f'Cannot read APK identity: {apk.name}')
        return match.groups(), info

    def certificate(apk):
        info = command(java, '-jar', signer, 'verify', '--print-certs', apk)
        match = re.search(r'Signer (?:#\d+|\([^)]*\)) certificate SHA-256 digest: ([0-9a-fA-F]+)', info)
        if not match:
            raise AssertionError(f'No signing certificate: {apk.name}: ' + repr(info[:600]))
        return match[1].lower()

    phone_id, _ = identity(args.phone_apk)
    if phone_id[0] != 'com.cosmotune.app':
        raise AssertionError('The reference must be the production phone package')
    phone_cert = certificate(args.phone_apk)
    apks = sorted(args.apk_dir.glob('*.apk'))
    if len(apks) != 4:
        raise AssertionError('Expected the three supported TV ABI APKs and one universal APK')
    supported = {'armeabi-v7a', 'arm64-v8a', 'x86_64'}
    seen = []
    for apk in apks:
        app_id, _ = identity(apk)
        if app_id != ('com.cosmotune.tv', *phone_id[1:]):
            raise AssertionError(f'TV package/version mismatch: {apk.name}')
        if certificate(apk) != phone_cert:
            raise AssertionError(f'TV/phone signing keys differ: {apk.name}')
        tree = command(aapt, 'dump', 'xmltree', apk, 'AndroidManifest.xml')
        activity = tree.find('"com.cosmotune.music.TvActivity"')
        alias = tree.find('E: activity-alias ')
        if activity < 0 or alias < 0 or activity >= alias:
            raise AssertionError(f'TV target must precede its launcher alias: {apk.name}')
        alias_tail = tree[alias:]
        alias_block = re.split(r'\n      E: ', alias_tail, maxsplit=1)[0]
        if not re.search(r'android:targetActivity[^\n]*"com.cosmotune.music.TvActivity"', alias_block):
            raise AssertionError(f'Launcher alias targets the wrong activity: {apk.name}')
        for expected in ('"com.cosmotune.music.MainActivity"', '"android.intent.action.MAIN"',
                         '"android.intent.category.LEANBACK_LAUNCHER"', '"android.intent.category.LAUNCHER"'):
            if expected not in alias_block:
                raise AssertionError(f'Incomplete launcher alias: {apk.name}: {expected}')
        if not re.search(r'android:exported[^\n]*0xffffffff', alias_block):
            raise AssertionError(f'TV launcher alias is not exported: {apk.name}')
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
            if not abis or not abis <= supported or len(abis) not in (1, 3):
                raise AssertionError(f'Unsupported native ABI set: {apk.name}: {abis}')
            if any(name.startswith('assets/wear/') for name in names):
                raise AssertionError(f'TV must not bundle the phone/watch companion: {apk.name}')
            if not any(name.startswith('models/') and name.endswith('.onnx') for name in names):
                raise AssertionError(f'No bundled beat/canvas model: {apk.name}')
        seen.append(frozenset(abis))
        with apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        print(f'OK {apk.name}: {apk.stat().st_size:,} bytes; {", ".join(sorted(abis))}; SHA256 {digest}')
    if set(seen) != {frozenset([abi]) for abi in supported} | {frozenset(supported)}:
        raise AssertionError('TV splits do not cover each supported ABI exactly once')
    print('Verified TV identity, version, certificate, launcher/alias and native package separation')


if __name__ == '__main__':
    main()
