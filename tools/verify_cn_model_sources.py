#!/usr/bin/env python3
"""Probe only the already pinned RTranslator mirrors; no source guessing or credential forwarding."""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/nie/translator/rtranslator/downloader/VerifiedModelMirrors.java'


def check(item, mappings):
    name = item['name']
    url = mappings[item['canonical']]
    full = name.startswith('Whisper_')
    size = int(item['size'])
    start = min(1024 * 1024, size // 2)
    length = min(4096, size - start)
    headers = {'Accept-Encoding': 'identity', 'User-Agent': 'RTranslator-source-regression-audit/1'}
    if not full:
        headers['Range'] = f'bytes={start}-{start + length - 1}'
    output = {'name': name, 'mode': 'full-sha256' if full else 'nonzero-range', 'passed': False}
    try:
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=25) as response:
            output['http_status'] = response.status
            if response.headers.get_content_type() in ('text/html', 'application/xhtml+xml'):
                raise ValueError('HTML is not a model')
            if not full:
                expected_range = f'bytes {start}-{start + length - 1}/{size}'
                if response.status != 206 or response.headers.get('Content-Range') != expected_range:
                    raise ValueError('Incorrect range response')
                payload = response.read(length + 1)
                if len(payload) != length:
                    raise ValueError('Incorrect range length')
                output['verified_bytes'] = length
            else:
                h = hashlib.sha256(); received = 0
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    received += len(chunk)
                    if received > size:
                        raise ValueError('File exceeds pinned size')
                    h.update(chunk)
                digest = h.hexdigest()
                if received != size or digest != item['sha256']:
                    raise ValueError('Size or pinned SHA-256 mismatch')
                output.update(verified_bytes=received, sha256=digest)
            output['passed'] = True
    except Exception as error:
        output['error'] = type(error).__name__ + ': ' + str(error)[:300]
    return output


def main():
    args = argparse.ArgumentParser()
    args.add_argument('--output', default='app/build/verification/current-model-source-probe.json')
    opts = args.parse_args()
    mappings = dict(re.findall(r'URLS\.put\("([^"]+)", "([^"]+)"\)', JAVA.read_text()))
    recorded = json.loads((ROOT / 'reports/verified-modelscope.json').read_text())
    items = [x for x in recorded['verified'] if x['canonical'] in mappings]
    assert len(items) == 60, 'Lost a verified mirror during integration'
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda item: check(item, mappings), items))
    data = {'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'network': 'GitHub-hosted cloud runner; NOT a mainland mobile network test',
            'passed': sum(x['passed'] for x in results), 'total': len(results), 'results': results}
    path = ROOT / opts.output; path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
    print(json.dumps({'passed': data['passed'], 'total': data['total']}))
    # Probe failure is an explicit build diagnostic, not silently labelled a network success.
    return 0 if data['passed'] == data['total'] else 2

if __name__ == '__main__':
    raise SystemExit(main())
