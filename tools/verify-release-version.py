#!/usr/bin/env python3
"""Reject a release tag whose native app and browser helper identities disagree."""
import argparse
import json
from pathlib import Path
import re
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    version = args.version.removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', version):
        parser.error('Use a semantic release version.')
    root = Path(__file__).resolve().parents[1]
    paths = ('build.gradle.kts', 'desktopApp/build.gradle.kts')
    values = {}
    for name in paths:
        source = (root / name).read_text(encoding='utf-8')
        match = re.search(r'gradleProperty\("cosmotune\.version"\)\.getOrElse\("([^"]+)"\)', source)
        if not match and name == 'desktopApp/build.gradle.kts':
            match = re.search(r'val appVersion: String =.*?\?:\s*"([^"]+)"', source, re.DOTALL)
        if not match:
            print(f'ERROR: No default release version in {name}', file=sys.stderr)
            return 1
        values[name] = match.group(1)
    helper = 'web/extension/package.json'
    values[helper] = json.loads((root / helper).read_text(encoding='utf-8'))['version']
    fallback = re.compile(r'System\.getProperty\("cosmotune\.version"\) \?: "([^"]+)"')
    for path in (root / 'desktopApp/src/main/kotlin').rglob('*.kt'):
        for index, match in enumerate(fallback.finditer(path.read_text(encoding='utf-8'))):
            values[f'{path.relative_to(root)} fallback {index}'] = match.group(1)
    provider_literal = re.compile(r'"Cosmotune/(\d[^"]*)"|"appVersion" -> JsonPrimitive\("([^"]+)"\)')
    for path in (root / 'providers/src/jvmSharedMain/kotlin').rglob('*.kt'):
        for index, match in enumerate(provider_literal.finditer(path.read_text(encoding='utf-8'))):
            values[f'{path.relative_to(root)} literal {index}'] = match.group(1) or match.group(2)
    check = re.search(r'package-web\.py --version (\S+)', (root / '.github/workflows/check.yml').read_text(encoding='utf-8'))
    values['.github/workflows/check.yml browser packaging'] = check.group(1) if check else '<missing>'
    errors = [f'{name}: {value} (expected {version})' for name, value in values.items() if value != version]
    if errors:
        print('ERROR: Release version mismatch:\n' + '\n'.join(errors), file=sys.stderr)
        return 1
    print(f'OK: native apps and browser helper are all {version}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
