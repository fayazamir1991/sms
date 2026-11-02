"""Check the distributable APK itself on Android 14 with networking switched off."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import time
from version import read_version

PACKAGE = 'ir.delneveshte.app'
APK = Path('release-input') / ('delneveshte-' + read_version()[1] + '.apk')

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT)

def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/catalog-startup.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/catalog-startup.xml'))

def count(root):
    for node in root.iter('node'):
        match = re.fullmatch(r'پیام‌ها:\s*([0-9۰-۹٠-٩]+)', node.get('text', ''))
        if match:
            value = int(match.group(1))
            assert value > 0, 'The offline catalog is visibly empty in the actual APK'
            return value
    raise AssertionError('The message counter is missing from the visible screen')

def launch():
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    return ui()

adb('install', '-r', str(APK))
adb('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1')
adb('shell', 'svc', 'wifi', 'disable')
adb('shell', 'svc', 'data', 'disable')
root = launch()
print('PASS: first offline launch shows', count(root), 'messages')
for category in ['کوتاه', 'همه']:
    root = ui()
    node = next((n for n in root.iter('node') if n.get('text') == category), None)
    assert node is not None, 'Category chip is missing: ' + category
    coordinates = [int(value) for value in re.findall(r'\d+', node.get('bounds', ''))]
    assert len(coordinates) == 4
    x1, y1, x2, y2 = coordinates
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    print('PASS: offline category', category, 'shows', count(ui()), 'messages')
print('PASS: repeated offline cold launch shows', count(launch()), 'messages')
