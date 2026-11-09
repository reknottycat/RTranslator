"""Read-only discovery: do not substitute similarly named models without byte identity."""
import concurrent.futures as cf
import hashlib
import json
import pathlib
import re
import time
import urllib.parse
from datetime import datetime, timezone
import requests

text = pathlib.Path('app/src/main/java/nie/translator/rtranslator/downloader/ModelDownloadManifest.java').read_text()
items = re.findall(r'add\("(https://[^"]+)", (\d+)L, (null|"[a-f0-9]+")\)', text)
manifest = {url: {'size': int(size), 'sha256': None if sha == 'null' else sha.strip('"')} for url, size, sha in items}
names = {url.rsplit('/', 1)[-1]: data for url, data in manifest.items()}
report = {'checked_at': datetime.now(timezone.utc).isoformat(), 'network': 'GitHub-hosted Ubuntu cloud runner; NOT a mainland device', 'searches': [], 'candidate_files': [], 'official_hashes': [], 'proxy_ranges': []}
headers = {'User-Agent': 'RTranslator-source-audit/1.0', 'Accept-Encoding': 'identity'}

def query(url, label):
    try:
        r = requests.get(url, headers=headers, timeout=(12, 25))
        result = {'label': label, 'url': url, 'status': r.status_code}
        try:
            data = r.json()
        except ValueError:
            result['non_json'] = True
            data = {}
        result['result'] = data
        return result
    except requests.RequestException as e:
        return {'label': label, 'url': url, 'error': type(e).__name__}

hf_ids = set()
for term in ['RTranslator', 'OnnxModelsEnhancer', 'Whisper_cache_initializer', 'HY-MT1.5-1.8B-ONNX']:
    r = query('https://huggingface.co/api/models?' + urllib.parse.urlencode({'search': term, 'limit': 50}), 'HF search ' + term)
    if isinstance(r.get('result'), list):
        hf_ids.update(x['id'] for x in r['result'] if 'id' in x)
        r['result'] = [x.get('id') for x in r['result']]
    report['searches'].append(r)
for ident in sorted(hf_ids)[:30]:
    r = query('https://huggingface.co/api/models/' + ident + '/tree/main?recursive=true&expand=false', 'HF files ' + ident)
    if isinstance(r.get('result'), list):
        r['result'] = [{k: x.get(k) for k in ['path', 'size', 'lfs']} for x in r['result'] if x.get('type') == 'file']
    report['candidate_files'].append(r)
for term in ['RTranslator', 'OnnxModelsEnhancer', 'Whisper_cache_initializer', 'HY-MT1.5-1.8B']:
    r = query('https://www.modelscope.cn/openapi/v1/models?' + urllib.parse.urlencode({'search': term, 'page_size': 50}), 'ModelScope search ' + term)
    report['searches'].append(r)
for ident in ['Tencent-Hunyuan/HY-MT1.5-1.8B', 'AI-ModelScope/whisper-small', 'AI-ModelScope/madlad400-3b-mt']:
    r = query('https://www.modelscope.cn/api/v1/models/' + ident + '/repo/files?Revision=master&Recursive=true', 'ModelScope files ' + ident)
    data = r.get('result', {})
    if isinstance(data, dict) and isinstance(data.get('Data'), dict) and 'Files' in data['Data']:
        r['result'] = [{k: x.get(k) for k in ['Path', 'Size', 'Sha256', 'Type']} for x in data['Data']['Files']]
    report['candidate_files'].append(r)

def official_hash(item):
    url, data = item
    name = url.rsplit('/', 1)[-1]
    started = time.monotonic()
    try:
        h, size = hashlib.sha256(), 0
        with requests.get(url, headers=headers, stream=True, timeout=(15, 45)) as r:
            r.raise_for_status()
            for chunk in r.iter_content(1024 * 1024):
                h.update(chunk)
                size += len(chunk)
        assert size == data['size'], (size, data['size'])
        result = {'name': name, 'url': url, 'size': size, 'sha256': h.hexdigest(), 'seconds': round(time.monotonic()-started, 2)}
    except Exception as e:
        result = {'name': name, 'url': url, 'error': str(e)[:180]}
    print('OFFICIAL_HASH ' + json.dumps(result), flush=True)
    return result
with cf.ThreadPoolExecutor(max_workers=3) as pool:
    report['official_hashes'] = list(pool.map(official_hash, [(u,d) for u,d in manifest.items() if d['sha256'] is None]))

asset = 'https://github.com/niedev/RTranslator/releases/download/2.0.0/Whisper_encoder.onnx'
expected_size = manifest[asset]['size']
references = {}
for start, end in [(0, 4194303), (4194304, 8388607)]:
    try:
        r = requests.get(asset, headers={**headers, 'Range': f'bytes={start}-{end}'}, timeout=(15,45))
        if r.status_code == 206 and r.headers.get('Content-Range') == f'bytes {start}-{end}/{expected_size}' and len(r.content) == end-start+1:
            references[start] = hashlib.sha256(r.content).hexdigest()
    except Exception:
        pass

def probe(args):
    prefix, start, end = args
    t = time.monotonic()
    result = {'source': prefix, 'start': start, 'end': end}
    try:
        with requests.get(prefix + asset, headers={**headers, 'Range': f'bytes={start}-{end}'}, timeout=(10,20), stream=True) as r:
            result.update(status=r.status_code, content_type=r.headers.get('Content-Type'), content_range=r.headers.get('Content-Range'))
            body = bytearray()
            for piece in r.iter_content(65536):
                body.extend(piece)
                if len(body) > end-start+1: break
            result['bytes'] = len(body)
            result['valid_range'] = r.status_code == 206 and r.headers.get('Content-Range') == f'bytes {start}-{end}/{expected_size}' and len(body) == end-start+1
            result['matches_official'] = hashlib.sha256(body).hexdigest() == references.get(start)
    except Exception as e:
        result['error'] = type(e).__name__
    result['seconds'] = round(time.monotonic()-t, 2)
    print('PROXY_RANGE ' + json.dumps(result), flush=True)
    return result
sources = ['https://gh-proxy.org/', 'https://ghproxy.imciel.com/', 'https://gh-proxy.com/']
with cf.ThreadPoolExecutor(max_workers=3) as pool:
    report['proxy_ranges'] = list(pool.map(probe, [(p,s,e) for p in sources for s,e in [(0,4194303),(4194304,8388607)]]))
pathlib.Path('source-audit.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
for section in ['searches', 'candidate_files']:
    for row in report[section]:
        print('CATALOG ' + json.dumps(row, ensure_ascii=False), flush=True)
