"""Build this dependency-free Java Android app using the official Android SDK tools."""
import os
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parent
if not os.environ.get('ANDROID_HOME') or not os.environ.get('JAVA_HOME'):
    raise SystemExit('Set ANDROID_HOME to the Android SDK and JAVA_HOME to JDK 17 or 21 before building.')
sdk = Path(os.environ['ANDROID_HOME'])
jdk = Path(os.environ['JAVA_HOME'])
tools = sdk / 'build-tools/35.0.0'
android = sdk / 'platforms/android-35/android.jar'
out = root / 'build/direct'
for name in ['classes', 'generated', 'dex']:
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
    '--min-sdk-version', '26', '--target-sdk-version', '34', '--version-code', '1',
    '--version-name', '0.1.0', '--java', out/'generated', '-o', out/'base.apk', out/'resources.zip')
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
key = out/'debug.keystore'
if not key.exists():
    run(jdk/'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
        '-keypass', 'android', '-alias', 'androiddebugkey', '-dname',
        'CN=Android Debug,O=Delneveshte,C=IR', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650')
apk = out/'delneveshte-0.1.0.apk'
run(jdk/'bin/java', '-jar', tools/'lib/apksigner.jar', 'sign', '--ks', key,
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, out/'aligned.apk')
run(jdk/'bin/java', '-jar', tools/'lib/apksigner.jar', 'verify', '--verbose', apk)
run(tools/'zipalign', '-c', '4', apk)
print('APK:', apk, flush=True)
