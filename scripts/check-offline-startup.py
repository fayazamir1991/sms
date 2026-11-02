"""Check the distributable APK itself on Android 14 with networking switched off."""
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import time
from version import read_version

PACKAGE = 'ir.delneveshte.app'
PREVIEWS = Path('docs/previews') / ('v' + read_version()[1])
PREVIEWS.mkdir(parents=True, exist_ok=True)

def screenshot(name):
    with (PREVIEWS / ('preview-' + name + '.png')).open('wb') as output:
        subprocess.run(['adb', 'exec-out', 'screencap', '-p'], stdout=output, check=True)

APK = Path('release-input') / ('delneveshte-' + read_version()[1] + '.apk')

def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT)

def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/catalog-startup.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/catalog-startup.xml'))

def count(root):
    for node in root.iter('node'):
        match = re.match(r'پیام‌ها:\s*([0-9۰-۹٠-٩]+)', node.get('text', ''))
        if match:
            value = int(match.group(1))
            assert value > 0, 'The offline catalog is visibly empty in the actual APK'
            return value
    raise AssertionError('The message counter is missing from the visible screen')

def launch():
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    return ui()

adb('install', '-r', 'release-input/delneveshte-0.8.1.apk')
adb('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1')
adb('shell', 'svc', 'wifi', 'disable')
adb('shell', 'svc', 'data', 'disable')
root = launch()
assert count(root)>0
adb('install','-r',str(APK))
root = launch()
print('PASS: preserved-signature update from 0.8.1, first offline launch shows', count(root), 'messages')
screenshot('home')
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

# The dedicated search page searches live without a submit button, and clear restores defaults.
def tap_node(root, *, text=None, description=None):
    node=next((n for n in root.iter('node') if (text is not None and n.get('text')==text) or (description is not None and n.get('content-desc')==description)),None)
    assert node is not None, 'Missing UI control: '+str(text or description)
    x1,y1,x2,y2=[int(v) for v in re.findall(r'\d+',node.get('bounds',''))]
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))

root=ui()
card=next(n for n in root.iter('node') if any(c.get('class')=='android.widget.TextView' and len(c.get('text',''))>25 for c in n) and sum(1 for d in n.iter('node') if d.get('class')=='android.widget.ImageButton')==2)
body=next(c for c in card if c.get('class')=='android.widget.TextView')
tap_node(root,text=body.get('text'))
menu=ui();labels={n.get('text') for n in menu.iter('node')}
screenshot('message-menu')
assert {'ارسال همین حالا','ارسال در زمان تصادفی','ارسال در زمان دلخواه','ویرایش متن','اطلاعات منبع'}<=labels
adb('shell','input','keyevent','4')
print('PASS: compact message card has two icon actions and tap opens the full context menu.')
tap_node(ui(),text='جست‌وجو')
root=ui();field=next(n for n in root.iter('node') if n.get('class')=='android.widget.EditText')
x1,y1,x2,y2=[int(v) for v in re.findall(r'\d+',field.get('bounds'))]
adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));adb('shell','input','text','zzzz')
root=ui();assert any(n.get('text','').startswith('پیام‌ها: ۰') for n in root.iter('node')), 'Typing must change results without submitting'
tap_node(root,description='پاک‌کردن جست‌وجو');assert count(ui())>0
print('PASS: dedicated live search changes results while typing, and clear restores messages without a search button.')

screenshot('search')
root=launch();tap_node(root,text='تنظیمات');ui();screenshot('settings')
print('PASS: real APK previews captured: home, context menu, live search and settings.')
