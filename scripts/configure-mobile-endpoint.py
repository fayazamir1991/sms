"""Publish only the nonsecret API URL; credentials stay in GitHub/Cloudflare Secrets."""
import base64
import json
import os
from pathlib import Path
import subprocess
import urllib.request

root = Path(__file__).resolve().parents[1]
metadata = json.loads((root / 'server/.local/deployment.json').read_text())
url = metadata['api_url']
if not url.startswith('https://') or not url.endswith('.workers.dev'):
    raise SystemExit('Unexpected deployment URL')
with urllib.request.urlopen(url + '/healthz', timeout=30) as response:
    if response.status != 200:
        raise SystemExit('Service health check failed')
repository = os.environ['GITHUB_REPOSITORY']
endpoint = f'repos/{repository}/contents/app/api-base-url.txt'
existing = subprocess.run(['gh', 'api', endpoint], text=True, capture_output=True)
old = json.loads(existing.stdout) if existing.returncode == 0 else None
old_url = base64.b64decode(old['content']).decode().strip() if old else None
if old_url != url:
    payload = {'message': 'Configure the deployed Cloudflare API endpoint', 'branch': 'main',
               'content': base64.b64encode((url + '\n').encode()).decode()}
    if old:
        payload['sha'] = old['sha']
    file = Path(os.environ['RUNNER_TEMP']) / 'mobile-endpoint.json'
    file.write_text(json.dumps(payload))
    subprocess.run(['gh', 'api', '--method', 'PUT', endpoint, '--input', str(file), '--silent'], check=True)
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
    summary.write(f'Backend: {url}\n\nAdmin: {metadata["admin_url"]}\n')
# Commits made with GITHUB_TOKEN do not trigger another push workflow.
subprocess.run(['gh', 'workflow', 'run', 'android.yml', '--ref', 'main'], check=True)
print('Live backend verified and the Android build requested.')
