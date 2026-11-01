"""Fail publication if an APK cannot update the owner's existing installation."""
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1])
sdk = Path(os.environ['ANDROID_HOME'])
signer = sdk / 'build-tools/35.0.0/apksigner'
result = subprocess.run([str(signer), 'verify', '--print-certs', str(apk)], text=True, capture_output=True, check=True)
match = re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)', result.stdout)
expected = (root / 'signing-certificate.sha256').read_text().strip()
if not match or match.group(1) != expected:
    raise SystemExit('Publication blocked: signing certificate does not match existing installations.')
print('APK signature verified: compatible with the existing chat installation.')
