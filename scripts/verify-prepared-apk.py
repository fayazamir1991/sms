"""Verify a publicly distributable, locally signed APK without uploading its private key."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
from version import read_version

root = Path(__file__).resolve().parents[1]
code, version = read_version(root)
apk = root / 'release-input' / f'delneveshte-{version}.apk'
metadata = json.loads(apk.with_suffix('.json').read_text())
source = hashlib.sha256()
files = [root / 'app/build.gradle', root / 'app/api-base-url.txt', root / 'signing-certificate.sha256']
files += [p for p in (root / 'app/src/main').rglob('*') if p.is_file()]
for file in sorted(files):
    source.update(str(file.relative_to(root)).encode() + b'\0' + file.read_bytes() + b'\0')
if metadata['source_sha256'] != source.hexdigest():
    raise SystemExit('Prepared APK does not match current application sources; rebuild it or use the preserved signing secrets.')
if metadata['apk_sha256'] != hashlib.sha256(apk.read_bytes()).hexdigest():
    raise SystemExit('Prepared APK checksum mismatch.')
endpoint = (os.environ.get('API_BASE_URL') or (root / 'app/api-base-url.txt').read_text()).strip().rstrip('/')
if endpoint != metadata['api_url'] or not endpoint.startswith('https://'):
    raise SystemExit('Prepared APK endpoint does not match the configured server.')
with zipfile.ZipFile(apk) as archive:
    if not any(endpoint.encode() in archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name)):
        raise SystemExit('Configured endpoint is absent from the APK.')
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
badging = subprocess.check_output([str(sdk / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
package = next(line for line in badging.splitlines() if line.startswith('package:'))
for field, expected in [('name', 'ir.delneveshte.app'), ('versionCode', str(code)), ('versionName', version)]:
    match = re.search(r"\b" + field + r"='([^']+)'", package)
    if not match or match.group(1) != expected:
        raise SystemExit('Prepared APK application/version mismatch: ' + field)
subprocess.run([sys.executable, str(root / 'scripts/verify-apk-signing.py'), str(apk)], check=True)
print('Prepared APK verified: source fingerprint, checksum, server URL, version and original signature.')
