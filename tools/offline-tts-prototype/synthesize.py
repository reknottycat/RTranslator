import time,wave,json,pathlib,numpy as np
from piper import PiperVoice
import os
root=pathlib.Path(os.environ['TTS_WORK_DIR']).resolve();results=[]
import socket
import onnxruntime
onnxruntime.disable_telemetry_events()
def blocked(*a,**kw):raise RuntimeError('Network disabled in synthesis test')
socket.socket.connect=blocked
socket.create_connection=blocked
for name,text in [('zh_CN-chaowen-medium','你好，这是中文离线语音测试。翻译已经准备好了。'),('ru_RU-denis-medium','Здравствуйте! Это проверка русского голоса. Перевод работает без интернета.'),('en_US-norman-medium','Hello. This is an offline English speech test. The translation is ready.')]:
 try:
  card=(root/name/'MODEL_CARD').read_text();print(card,flush=True)
  t=time.perf_counter();v=PiperVoice.load(root/name/(name+'.onnx'),download_dir=root);load=time.perf_counter()-t
  dest=root/(name+'.wav');t=time.perf_counter()
  with wave.open(str(dest),'wb') as w:v.synthesize_wav(text,w)
  elapsed=time.perf_counter()-t
  with wave.open(str(dest),'rb') as w:
   dur=w.getnframes()/w.getframerate();a=np.frombuffer(w.readframes(w.getnframes()),dtype=np.int16).astype(float)
  r={'model':name,'text':text,'load_sec':load,'synthesis_sec':elapsed,'duration_sec':dur,'rtf':elapsed/dur,'rms':float(np.sqrt(np.mean(a*a))),'peak':float(abs(a).max()),'nonzero_samples':int(np.count_nonzero(a)),'file':str(dest)}
 except Exception as e:r={'model':name,'error':str(e)}
 results.append(r);print(json.dumps(r),flush=True)
(root/'results.json').write_text(json.dumps(results,indent=2,ensure_ascii=False))
import sys
sys.exit(1 if any('error' in row or row.get('nonzero_samples', 0) == 0 for row in results) else 0)
