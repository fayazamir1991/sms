"""Create owner-only setup files once; never print or commit their contents."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--source-keystore', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
destination = Path(args.output).resolve()
destination.mkdir(mode=0o700, parents=True, exist_ok=False)
keytool = str(Path(os.environ['JAVA_HOME']) / 'bin/keytool') if os.environ.get('JAVA_HOME') else shutil.which('keytool')
if not keytool:
    raise SystemExit('JDK keytool is required')
password = secrets.token_urlsafe(36)
env = os.environ.copy()
env['SMS_SOURCE_KEY_PASSWORD'] = env.get('SMS_SOURCE_KEY_PASSWORD', 'android')
env['SMS_DESTINATION_KEY_PASSWORD'] = password
keyfile = destination / 'preserved-signing.p12'
subprocess.run([keytool, '-importkeystore', '-noprompt', '-srckeystore', args.source_keystore,
               '-srcstorepass:env', 'SMS_SOURCE_KEY_PASSWORD', '-srcalias', 'androiddebugkey',
               '-destkeystore', str(keyfile), '-deststoretype', 'PKCS12',
               '-deststorepass:env', 'SMS_DESTINATION_KEY_PASSWORD',
               '-destkeypass:env', 'SMS_DESTINATION_KEY_PASSWORD', '-destalias', 'androiddebugkey'],
               env=env, check=True, capture_output=True)
certificate = subprocess.run([keytool, '-exportcert', '-keystore', str(keyfile), '-alias', 'androiddebugkey',
                              '-storepass:env', 'SMS_DESTINATION_KEY_PASSWORD'], env=env, check=True, capture_output=True).stdout
expected = (root / 'signing-certificate.sha256').read_text().strip()
if hashlib.sha256(certificate).hexdigest() != expected:
    raise SystemExit('Signing certificate differs from the APK already installed by the owner')
admin_password = secrets.token_urlsafe(32)
runtime = {'DATA_ENCRYPTION_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
           'SESSION_SIGNING_KEY': secrets.token_urlsafe(48),
           'ADMIN_TOKEN_SHA256': hashlib.sha256(admin_password.encode()).hexdigest()}
files = {'ANDROID_KEYSTORE_BASE64.txt': base64.b64encode(keyfile.read_bytes()).decode(),
         'ANDROID_SIGNING_PASSWORD.txt': password,
         'SERVER_RUNTIME_SECRETS.json': json.dumps(runtime, indent=2),
         'ADMIN_LOGIN.txt': admin_password}
for name, content in files.items():
    file = destination / name
    file.write_text(content, encoding='utf-8')
    file.chmod(0o600)
keyfile.chmod(0o600)
archive = destination.with_suffix('.zip')
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
    for file in destination.iterdir():
        bundle.write(file, file.name)
archive.chmod(0o600)
print('Private setup package prepared. Certificate matches the installed chat APK.')
print('Keep these files private and retain a backup; their contents were not printed.')
