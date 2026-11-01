import base64
import os
from pathlib import Path

encoded = os.environ.get('ANDROID_KEYSTORE_BASE64')
password = os.environ.get('ANDROID_SIGNING_PASSWORD')
if not encoded or not password:
    raise SystemExit('Signing secrets are missing. Add the prepared ANDROID_KEYSTORE_BASE64 and ANDROID_SIGNING_PASSWORD repository secrets. No incompatible APK will be published.')
root = Path(__file__).resolve().parents[1]
endpoint = (root / 'app/api-base-url.txt').read_text().strip() if (root / 'app/api-base-url.txt').exists() else ''
if not endpoint.startswith('https://'):
    raise SystemExit('Deploy the backend first. Its workflow configures the verified HTTPS API endpoint automatically.')
file = Path(os.environ['RUNNER_TEMP']) / 'preserved-signing.p12'
file.write_bytes(base64.b64decode(encoded.strip(), validate=True))
file.chmod(0o600)
with open(os.environ['GITHUB_ENV'], 'a') as output:
    output.write('ANDROID_SIGNING_STORE=' + str(file) + '\n')
print('Original signing key restored without exposing private material.')
