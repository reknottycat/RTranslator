"""Download verified resources only. Does not install software or run synthesis."""
import hashlib,json,pathlib,tarfile,urllib.request,argparse
p=argparse.ArgumentParser();p.add_argument('--work-dir',required=True);args=p.parse_args()
here=pathlib.Path(__file__).resolve().parent;root=pathlib.Path(args.work_dir).resolve();root.mkdir(parents=True,exist_ok=True)
def get(url,dest,sha):
 dest.parent.mkdir(parents=True,exist_ok=True)
 if not dest.exists() or hashlib.sha256(dest.read_bytes()).hexdigest()!=sha:urllib.request.urlretrieve(url,dest)
 if hashlib.sha256(dest.read_bytes()).hexdigest()!=sha:raise ValueError('SHA mismatch '+str(dest))
for row in json.loads((here/'manifest.json').read_text()):
 rel=pathlib.Path(row['file']);get(row['url'],root/rel.parent.name/rel.name,row['sha256'])
g=json.loads((here/'g2pw-manifest.json').read_text());archive=root/'g2pw.tar.gz';get(g['url'],archive,g['sha256'])
gdir=root/'g2pW';gdir.mkdir(exist_ok=True)
with tarfile.open(archive) as t:t.extractall(gdir,filter='data')
tok=root/'bert-tokenizer'
for row in json.loads((here/'tokenizer-manifest.json').read_text()):get(row['url'],tok/row['file'],row['sha256'])
# Piper's bundled g2pW converter reads model_source from this config.
# Point it to the directory containing local vocab.txt/tokenizer_config.json/config.json.
with (gdir/'config.py').open('a') as f:f.write('\nmodel_source = '+repr(str(tok))+'\n')
print(root)
