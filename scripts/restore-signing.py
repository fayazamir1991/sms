import base64
import os
from pathlib import Path
from urllib.parse import urlsplit

encoded = os.environ.get('ANDROID_KEYSTORE_BASE64')
password = os.environ.get('ANDROID_SIGNING_PASSWORD')
if not encoded or not password:
    raise SystemExit('Signing secrets are missing. Add the prepared ANDROID_KEYSTORE_BASE64 and ANDROID_SIGNING_PASSWORD repository secrets. No incompatible APK will be published.')
root = Path(__file__).resolve().parents[1]
endpoint = os.environ.get('API_BASE_URL', '').strip() or ((root / 'app/api-base-url.txt').read_text().strip() if (root / 'app/api-base-url.txt').exists() else '')
parsed = urlsplit(endpoint)
if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
    raise SystemExit('Set the deployed HTTPS server origin in the API_BASE_URL repository variable, or deploy the optional Cloudflare backend first.')
file = Path(os.environ['RUNNER_TEMP']) / 'preserved-signing.p12'
file.write_bytes(base64.b64decode(encoded.strip(), validate=True))
file.chmod(0o600)
with open(os.environ['GITHUB_ENV'], 'a') as output:
    output.write('ANDROID_SIGNING_STORE=' + str(file) + '\n')
print('Original signing key restored without exposing private material.')
