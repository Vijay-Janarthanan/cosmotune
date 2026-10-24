#!/usr/bin/env python3
"""Verify a phone's embedded Wear APK, identity/version, and production signing parity."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import zipfile


def command(*args):
    return subprocess.check_output([str(a) for a in args], stderr=subprocess.STDOUT, text=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk-dir', type=Path, required=True)
    parser.add_argument('--wear-apk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path)
    parser.add_argument('--allow-unsigned', action='store_true', help='Local packaging checks only; never use for published APKs')
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
        text = command(aapt, 'dump', 'badging', apk)
        match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text)
        if not match:
            raise AssertionError(f'Cannot read APK identity: {apk.name}')
        return match.groups(), text

    def certificate(apk):
        try:
            text = command(java, '-jar', signer, 'verify', '--print-certs', apk)
        except subprocess.CalledProcessError:
            if args.allow_unsigned:
                return None
            raise AssertionError(f'APK signature verification failed: {apk.name}') from None
        match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', text)
        if not match:
            raise AssertionError(f'No signing certificate: {apk.name}')
        return match[1].lower()

    watch_bytes = args.wear_apk.read_bytes()
    watch_hash = hashlib.sha256(watch_bytes).hexdigest()
    watch_identity, watch_info = identity(args.wear_apk)
    if "android.hardware.type.watch" not in watch_info:
        raise AssertionError('Companion APK does not declare watch hardware')
    watch_cert = certificate(args.wear_apk)
    phones = sorted(args.apk_dir.glob('*.apk'))
    if not phones:
        raise AssertionError('No phone APKs found')
    for phone in phones:
        phone_identity, _ = identity(phone)
        if phone_identity != watch_identity:
            raise AssertionError(f'Phone/watch package or version mismatch: {phone.name}')
        if certificate(phone) != watch_cert:
            raise AssertionError(f'Phone/watch signing keys differ: {phone.name}')
        with zipfile.ZipFile(phone) as archive:
            embedded = archive.read('assets/wear/CosmotuneWear.apk')
            recorded_hash = archive.read('assets/wear/CosmotuneWear.sha256').decode('ascii').strip()
        if embedded != watch_bytes or recorded_hash != watch_hash:
            raise AssertionError(f'Embedded companion/hash mismatch: {phone.name}')
        print(f'OK {phone.name}: matching embedded companion, package/version and signing certificate')
    print(f'Wear APK: {len(watch_bytes):,} bytes, SHA256 {watch_hash}')


if __name__ == '__main__':
    main()
