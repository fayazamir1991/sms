"""Read the release version from the Android module's single source of truth."""
import re
from pathlib import Path


def read_version(root=None):
    root = Path(root) if root else Path(__file__).resolve().parents[1]
    config = (root / 'app/build.gradle').read_text(encoding='utf-8')
    code = re.search(r'\bversionCode\s+(\d+)', config)
    name = re.search(r'''\bversionName\s+['"]([0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?)['"]''', config)
    if not code or not name:
        raise ValueError('Missing versionCode or semantic versionName in app/build.gradle')
    return int(code.group(1)), name.group(1)


if __name__ == '__main__':
    print(read_version()[1])
