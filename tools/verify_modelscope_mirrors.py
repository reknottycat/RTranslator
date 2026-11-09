"""Verify mirrors against upstream digests; writes evidence and a generated fixed allowlist."""
import concurrent.futures as cf
import hashlib
import json
import pathlib
import re
import time
import urllib.parse as up
from datetime import datetime, timezone
import requests

HEADERS = {'User-Agent': 'RTranslator-exact-model-verification/1.0', 'Accept-Encoding': 'identity'}
# Computed from direct official GitHub Release bytes in Actions run 42647267376.
PINNED = {
'NLLB_cache_initializer.onnx':'4150b3cba7db9487a94e3b693f79d0f89772e75d80bffd0e60b06de1b574e3d9',
'NLLB_decoder.onnx':'86028825495fc34085c7ebb80fb5f0eaa18ac6600f9d4dbc55bc731cc6981159',
'NLLB_embed_and_lm_head.onnx':'2745a6f932237b8bb0c35ded54987ea3fc455b696215d48d32271c28c785dd50',
'NLLB_encoder.onnx':'9b25d62f4875e5087ffde53c2794308c07f60f4c77670fc5bbfe308a1ef88cd9',
'Whisper_cache_initializer.onnx':'6e303b22e38ea5a2f6c1e4c29ddbf11b047f77d8e7a99841182e709ee6b2d9d1',
'Whisper_cache_initializer_batch.onnx':'6da66ede8430347e314a7341fa05481778ac945d5b028106a8340361fdc99986',
'Whisper_decoder.onnx':'704de24c96a812779f3d0d7ed2a47a37b070f44abb0d92ca5e19d31c727718df',
'Whisper_detokenizer.onnx':'713447d18dc0f4cae0759f616aba98a909100185941f80844bf954ef12888e12',
'Whisper_encoder.onnx':'0171be36c908738326f70c8a7f58049ba4786a07d7ea79c0e0f9e26550cdfb50',
'Whisper_initializer.onnx':'dbf3281f2399b46527afbf0401a91174596222b79fa4e6646b0f2d49fb397d85'}
text = pathlib.Path('app/src/main/java/nie/translator/rtranslator/downloader/ModelDownloadManifest.java').read_text()
manifest = {}
for url, size, digest in re.findall(r'add\("(https://[^"]+)", (\d+)L, (null|"[a-f0-9]+")\)', text):
    name = url.rsplit('/',1)[-1]
    manifest[name] = {'canonical':url, 'name':name, 'size':int(size), 'sha256':PINNED.get(name) if digest=='null' else digest.strip('"')}

def get_json(url):
    r=requests.get(url,headers=HEADERS,timeout=(12,35)); r.raise_for_status(); return r.json()
report={'date':datetime.now(timezone.utc).isoformat(), 'official_hash_evidence':'https://github.com/reknottycat/RTranslator/actions/runs/42647267376', 'network':'Azure eastus cloud runner, not mainland Android', 'search_pages':[], 'candidates':[], 'verified':[], 'errors':[]}
ids=set()
for page in range(1,10):
    result=get_json('https://www.modelscope.cn/openapi/v1/models?'+up.urlencode({'search':'RTranslator','page_size':50,'page_number':page}))
    data=result['data']; rows=data['models']; ids.update(m['id'] for m in rows)
    report['search_pages'].append({'page':page,'count':len(rows),'total':data['total_count']})
    if page*50>=data['total_count'] or not rows: break

def inspect(ident):
    try:
        url='https://www.modelscope.cn/api/v1/models/'+ident+'/repo/files?Revision=master&Recursive=true'
        data=get_json(url)
        if not data.get('Success',False): return [], {'repo':ident,'error':data.get('Message')}
        matches=[]
        for item in data.get('Data',{}).get('Files',[]):
            filename=item.get('Path','').rsplit('/',1)[-1]
            if filename not in manifest: continue
            expected=manifest[filename]
            m={**expected,'repo':ident,'path':item['Path'],'mirror_size':item.get('Size'),'mirror_sha256':item.get('Sha256')}
            m['metadata_matches']=m['size']==m['mirror_size'] and m['sha256']==m['mirror_sha256']
            m['url']='https://www.modelscope.cn/api/v1/models/'+ident+'/repo?'+up.urlencode({'Revision':'master','FilePath':item['Path']})
            matches.append(m)
        return matches, None
    except Exception as e: return [], {'repo':ident,'error':type(e).__name__+': '+str(e)[:120]}
with cf.ThreadPoolExecutor(max_workers=5) as pool:
    for matches,error in pool.map(inspect,sorted(ids)):
        report['candidates'].extend(matches)
        if error: report['errors'].append(error)

def verify(m):
    result=dict(m); t=time.monotonic()
    try:
        if not m['metadata_matches']: raise ValueError('Different size or SHA-256')
        if m['name'].startswith('Whisper_'):
            sha=hashlib.sha256(); count=0
            with requests.get(m['url'],headers=HEADERS,stream=True,timeout=(15,45)) as r:
                r.raise_for_status()
                for b in r.iter_content(1024*1024): sha.update(b); count+=len(b)
            result['whole_file_check']= count==m['size'] and sha.hexdigest()==m['sha256']
            if not result['whole_file_check']: raise ValueError('Downloaded whole file differs from official')
        start= min(4194304, max(0,m['size']-2048)); end=start+1023
        with requests.get(m['url'],headers={**HEADERS,'Range':f'bytes={start}-{end}'},stream=True,timeout=(12,25)) as r:
            result['range_status']=r.status_code
            result['content_range']=r.headers.get('Content-Range')
            data=r.raw.read(1025)
            result['range_bytes']=len(data)
            result['range_ok']=r.status_code==206 and r.headers.get('Content-Range')==f'bytes {start}-{end}/{m["size"]}' and len(data)==1024
            if not result['range_ok']: raise ValueError('Not an exact HTTP range')
        result['ok']=True
    except Exception as e: result.update(ok=False,error=type(e).__name__+': '+str(e)[:160])
    result['seconds']=round(time.monotonic()-t,2)
    print('VERIFIED_MATCH '+json.dumps(result,ensure_ascii=False),flush=True)
    return result
with cf.ThreadPoolExecutor(max_workers=5) as pool:
    results=list(pool.map(verify,report['candidates']))
report['verification']=results
report['verified']=[m for m in results if m.get('ok')]
report['unmatched_official_files']=sorted(set(manifest)-{m['name'] for m in report['verified']})
out=pathlib.Path('reports'); out.mkdir(exist_ok=True)
(out/'verified-modelscope.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
lines=['package nie.translator.rtranslator.downloader;','','import java.util.HashMap;','import java.util.Map;','', '/** Generated from reports/verified-modelscope.json. Mirrors are community-hosted; hashes pin upstream bytes. */','public final class VerifiedModelMirrors {','    private static final Map<String, String> URLS = new HashMap<>();','    private static final Map<String, String> DIGESTS = new HashMap<>();','    static {']
for m in sorted(report['verified'],key=lambda m:m['canonical']):
    lines.append('        URLS.put('+json.dumps(m['canonical'])+', '+json.dumps(m['url'])+');')
for m in manifest.values():
    if m['sha256']: lines.append('        DIGESTS.put('+json.dumps(m['canonical'])+', '+json.dumps(m['sha256'])+');')
lines += ['    }','    private VerifiedModelMirrors() {}','    public static String forUrl(String url) { return URLS.get(url); }','    public static String digestForUrl(String url) { return DIGESTS.get(url); }','}']
(out/'VerifiedModelMirrors.java').write_text('\n'.join(lines)+'\n')
print('SUMMARY '+json.dumps({'verified':len(report['verified']),'official_count':len(manifest),'unmatched':report['unmatched_official_files'],'errors':report['errors']},ensure_ascii=False))
if not all(name in {m['name'] for m in report['verified']} for name in PINNED if name.startswith('Whisper_')):
    raise SystemExit('Whisper mirror validation incomplete; do not claim full first-run coverage')
