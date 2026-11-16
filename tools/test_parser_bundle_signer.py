"""Publication fixtures: keys stay local to the temporary directory and are never printed."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import struct
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if os.environ.get('JAVA_HOME') else shutil.which('java')


class ParserBundleSignerTest(unittest.TestCase):
    @staticmethod
    def dex(name='org/koitharu/kotatsu/parsers/core/ParsersBundleImpl'):
        descriptor = ('L' + name + ';').encode()
        data = bytearray(154 + len(descriptor))
        data[:8] = b'dex\n035\0'
        for offset, value in [(32,len(data)),(36,112),(40,0x12345678),(56,1),(60,112),(64,1),(68,116),(96,1),(100,120),(112,152),(116,0),(120,0)]:
            struct.pack_into('<I', data, offset, value)
        data[152] = len(descriptor)
        data[153:153+len(descriptor)] = descriptor
        return bytes(data)
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='parser-signer-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        source = self.root / 'FixtureKey.java'
        source.write_text('''import java.security.*;import java.security.spec.*;import java.nio.file.*;import java.util.*;
public class FixtureKey{public static void main(String[]a)throws Exception{
var g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));var p=g.generateKeyPair();
Files.writeString(Path.of(a[0]),Base64.getEncoder().encodeToString(p.getPrivate().getEncoded()));
Files.writeString(Path.of(a[1]),"publicKey="+Base64.getEncoder().encodeToString(p.getPublic().getEncoded()));}}''', encoding='utf-8')
        subprocess.run([JAVA, str(source), str(self.root / 'key'), str(self.root / 'channel')], check=True, capture_output=True)

    def sign(self, runtime, entries, key=None):
        archive = self.root / 'input.zip'
        with zipfile.ZipFile(archive, 'w') as output:
            for name, value in entries:
                output.writestr(name, value)
        environment = dict(os.environ, PARSER_SIGNING_PRIVATE_KEY=key or (self.root / 'key').read_text())
        result = subprocess.run([JAVA, str(ROOT / 'tools/ParserBundleSigner.java'), runtime, '1794785322', 'a90648aebd',
                                 str(archive), str(self.root / 'out'), str(self.root / 'channel')], env=environment, capture_output=True)
        # Do not include process stderr/environment/key material in assertion diagnostics.
        return result.returncode

    def payload(self, runtime):
        envelope = json.loads((self.root / 'out' / f'parsers-{runtime}-v1.json').read_text())
        self.assertTrue(base64.b64decode(envelope['signature']))
        return json.loads(base64.b64decode(envelope['payload']))

    def test_jvm_manifest_matches_normalized_archive_and_no_foreign_namespace_is_signed(self):
        import hashlib
        self.assertEqual(0, self.sign('jvm', [('org/koitharu/kotatsu/parsers/core/ParsersBundleImpl.class', b'fixture'), ('META-INF/MANIFEST.MF', b'ignored')]))
        payload = self.payload('jvm')
        archive = self.root / 'out' / payload['file']
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), payload['sha256'])
        with zipfile.ZipFile(archive) as value:
            self.assertEqual(['org/koitharu/kotatsu/parsers/core/ParsersBundleImpl.class'], value.namelist())
        self.assertNotEqual(0, self.sign('jvm', [('com/unexpected/Payload.class', b'fixture')]))

    def test_android_manifest_covers_each_dex_including_double_digit_names(self):
        entries = [('classes.dex' if index == 1 else f'classes{index}.dex', self.dex() if index == 1 else self.dex(f'org/koitharu/kotatsu/parsers/core/Auxiliary{index}')) for index in range(1, 11)]
        self.assertEqual(0, self.sign('android', entries))
        payload = self.payload('android')
        self.assertEqual([value[0] for value in entries], [value['name'] for value in payload['dex']])
        self.assertEqual('android', payload['runtime'])
        self.assertEqual(1794785322, payload['version'])

    def test_unsafe_entries_and_non_dex_payloads_never_get_a_manifest(self):
        self.assertNotEqual(0, self.sign('android', [('../classes.dex', b'dex\n035\0fixture')]))
        self.assertFalse((self.root / 'out' / 'parsers-android-v1.json').exists())
        self.assertNotEqual(0, self.sign('android', [('classes.dex', b'not a dex')] ))
        self.assertFalse((self.root / 'out' / 'parsers-android-v1.json').exists())
        self.assertNotEqual(0, self.sign('android', [('classes2.dex', self.dex())]))

    def test_contract_replacement_and_missing_entry_point_fail_without_destroying_a_valid_publication(self):
        implementation = 'org/koitharu/kotatsu/parsers/core/ParsersBundleImpl.class'
        self.assertEqual(0, self.sign('jvm', [(implementation, b'fixture')]))
        payload = self.payload('jvm')
        artifact = self.root / 'out' / payload['file']
        before = artifact.read_bytes()
        manifest = (self.root / 'out' / 'parsers-jvm-v1.json').read_bytes()
        self.assertNotEqual(0, self.sign('jvm', [(implementation, b'new'), ('org/koitharu/kotatsu/parsers/model/ParserMangaSource.class', b'forbidden')]))
        self.assertEqual(before, artifact.read_bytes())
        self.assertEqual(manifest, (self.root / 'out' / 'parsers-jvm-v1.json').read_bytes())
        self.assertNotEqual(0, self.sign('jvm', [('org/koitharu/kotatsu/parsers/core/Other.class', b'fixture')]))
        self.assertNotEqual(0, self.sign('android', [('classes.dex', self.dex('org/koitharu/kotatsu/parsers/model/ParserMangaSource'))]))
        self.assertNotEqual(0, self.sign('jvm', [(implementation, b'different valid version contents')]))
        self.assertEqual(before, artifact.read_bytes())


if __name__ == '__main__':
    unittest.main()
