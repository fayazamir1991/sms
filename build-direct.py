"""Build this dependency-free Java Android app using the official Android SDK tools."""
import os
import json
import shutil
import sys
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from scripts.version import read_version
from pathlib import Path

root = Path(__file__).resolve().parent
version_code, version_name = read_version(root)
if not os.environ.get('ANDROID_HOME') or not os.environ.get('JAVA_HOME'):
    raise SystemExit('Set ANDROID_HOME to the Android SDK and JAVA_HOME to JDK 17 or 21 before building.')
sdk = Path(os.environ['ANDROID_HOME'])
jdk = Path(os.environ['JAVA_HOME'])
tools = sdk / 'build-tools/35.0.0'
android = sdk / 'platforms/android-35/android.jar'
out = root / 'build/direct'
for name in ['classes', 'generated', 'dex']:
    shutil.rmtree(out / name, ignore_errors=True)
    (out / name).mkdir(parents=True, exist_ok=True)
def run(*args):
    print('Running:', Path(str(args[0])).name, flush=True)
    subprocess.run([str(a) for a in args], cwd=root, check=True)
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
manifest = ET.parse(root/'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', 'ir.delneveshte.app')
manifest.write(out/'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
run(tools/'aapt2', 'compile', '--dir', root/'app/src/main/res', '-o', out/'resources.zip')
run(tools/'aapt2', 'link', '-I', android, '--manifest', out/'AndroidManifest.xml',
    '--min-sdk-version', '26', '--target-sdk-version', '34', '--version-code', str(version_code),
    '--version-name', version_name, '--java', out/'generated', '-o', out/'base.apk', out/'resources.zip')
endpoint = os.environ.get('API_BASE_URL') or ((root/'app/api-base-url.txt').read_text().strip() if (root/'app/api-base-url.txt').exists() else '')
if endpoint and not endpoint.startswith('https://'):
    raise SystemExit('API_BASE_URL must use HTTPS')
config = out/'generated/ir/delneveshte/app/BuildConfig.java'
config.parent.mkdir(parents=True, exist_ok=True)
config.write_text('package ir.delneveshte.app; public final class BuildConfig { public static final boolean DEBUG=false; public static final String APPLICATION_ID="ir.delneveshte.app"; public static final String API_BASE_URL='+json.dumps(endpoint)+'; public static final String VERSION_NAME='+json.dumps(version_name)+'; public static final int VERSION_CODE='+str(version_code)+'; }')
sources = sorted((root/'app/src/main/java').rglob('*.java')) + sorted((out/'generated').rglob('*.java'))
run(jdk/'bin/javac', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
    '-classpath', android, '-d', out/'classes', *sources)
with zipfile.ZipFile(out/'classes.jar', 'w') as z:
    for p in (out/'classes').rglob('*.class'):
        z.write(p, p.relative_to(out/'classes'))
run(jdk/'bin/java', '-cp', tools/'lib/d8.jar', 'com.android.tools.r8.D8',
    '--lib', android, '--min-api', '26', '--output', out/'dex', out/'classes.jar')
with zipfile.ZipFile(out/'base.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    for p in (out/'dex').glob('*.dex'): z.write(p, p.name)
run(tools/'zipalign', '-f', '4', out/'base.apk', out/'aligned.apk')
key = Path(os.environ.get('ANDROID_SIGNING_STORE', str(out/'debug.keystore')))
if not key.exists():
    raise SystemExit('The existing signing key is required. Never generate a replacement for an update.')
os.environ.setdefault('ANDROID_SIGNING_PASSWORD', 'android')
apk = out/('delneveshte-' + version_name + '.apk')
run(jdk/'bin/java', '-jar', tools/'lib/apksigner.jar', 'sign', '--ks', key,
    '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'env:ANDROID_SIGNING_PASSWORD', '--key-pass', 'env:ANDROID_SIGNING_PASSWORD', '--out', apk, out/'aligned.apk')
run(jdk/'bin/java', '-jar', tools/'lib/apksigner.jar', 'verify', '--verbose', apk)
run(tools/'zipalign', '-c', '4', apk)
run(sys.executable, root/'scripts/verify-apk-signing.py', apk)
print('APK:', apk, flush=True)
