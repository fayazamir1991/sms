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

def cards(root):
    return [node for node in root.iter('node') if any(child.get('class') == 'android.widget.TextView' and len(child.get('text', '')) > 25 for child in node) and sum(1 for child in node.iter('node') if child.get('class') == 'android.widget.ImageButton') == 2]

def count(root):
    value = len(cards(root))
    assert value > 0, 'No message cards are visible in the actual APK'
    assert not any(node.get('text', '').startswith('پیام‌ها:') for node in root.iter('node')), 'Message count must not be shown'
    return value

def ime_shown():
    state = adb('shell', 'dumpsys', 'input_method')
    return 'mInputShown=true' in state or 'mIsInputViewShown=true' in state

def wait_ui(check, description, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = ui()
        if check(root): return root
        time.sleep(.1)
    raise AssertionError(description)

def assert_focused_search():
    root = wait_ui(lambda root: any(n.get('class') == 'android.widget.EditText' and n.get('focused') == 'true' for n in root.iter('node')), 'Search must focus automatically')
    deadline = time.monotonic() + 10
    while not ime_shown() and time.monotonic() < deadline: time.sleep(.2)
    assert ime_shown(), 'The soft keyboard must open automatically'
    assert not cards(root), 'A newly opened search must have no messages'
    assert not any(n.get('text', '').startswith('پیام‌ها:') for n in root.iter('node'))
    return root

def launch():
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    return ui()

adb('install', '-r', 'release-input/delneveshte-0.8.1.apk')
adb('shell', 'settings', 'put', 'global', 'airplane_mode_on', '1')
adb('shell', 'svc', 'wifi', 'disable')
adb('shell', 'svc', 'data', 'disable')
root = launch()
assert any(re.match(r'پیام‌ها:\s*[1-9۱-۹]', n.get('text', '')) for n in root.iter('node')), 'Legacy APK did not load its catalog'
adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
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

# Search has no default list: focus/keyboard on entry, live results after typing, clear empties results.
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
root=assert_focused_search()
adb('shell','input','text','zzzz')
root=wait_ui(lambda root: any(n.get('text')=='پیامی پیدا نشد.' for n in root.iter('node')), 'Typing must produce a search result state without submitting')
assert not cards(root)
tap_node(root,description='پاک‌کردن جست‌وجو')
root=ui();assert not cards(root);assert not any(n.get('text')=='پیامی پیدا نشد.' for n in root.iter('node'))
inputs=[n for n in root.iter('node') if n.get('class')=='android.widget.EditText']
assert len(inputs)==1 and inputs[0].get('focused')=='true', [n.attrib for n in inputs]
# Accessibility may expose the hint as EditText.text when the actual value is empty.
assert any(n.get('content-desc')=='پاک‌کردن جست‌وجو' and n.get('enabled')=='false' for n in root.iter('node')), 'Clear must be disabled for the genuinely empty input'
print('PASS: search opens empty with focus/keyboard; typing searches live and clear removes results.')

if ime_shown(): adb('shell','input','keyevent','4')
ui();screenshot('search')
tap_node(ui(),description='بازگشت به پیشنهادها');assert count(ui())>0
tap_node(ui(),description='جست‌وجو');assert_focused_search();screenshot('search-keyboard')
print('PASS: header search action also opens an empty focused search and the keyboard automatically.')
root=launch()
compose=next(n for n in root.iter('node') if n.get('content-desc')=='نوشتن پیام')
coords=list(map(int,re.findall(r'\d+',compose.get('bounds'))));width=int(adb('shell','wm','size').strip().split(':')[-1].strip().split('x')[0]);assert (coords[0]+coords[2])/2>width/2,'Compose must be on the physical right in RTL'
bank=next(n for n in root.iter('node') if n.get('content-desc')=='بانک پیام‌ها');bounds=list(map(int,re.findall(r'\d+',bank.get('bounds'))));assert abs((bounds[0]+bounds[2])/2-width/2)<width/8,'Message bank must be centered'
tap_node(root,text='تنظیمات');root=ui()
choices=[n for n in root.iter('node') if n.get('class')=='android.widget.RadioButton'];assert len(choices)==2 and any(n.get('text')=='محاوره‌ای' and n.get('checked')=='true' for n in choices)
tap_node(root,text='رسمی');root=ui();assert any(n.get('text')=='رسمی' and n.get('checked')=='true' for n in root.iter('node') if n.get('class')=='android.widget.RadioButton');screenshot('settings')
print('PASS: compose is on the physical right; message bank is centered; tone uses two radio buttons side by side.')
print('PASS: actual APK previews captured: home, context menu, empty search, automatic keyboard and settings.')
