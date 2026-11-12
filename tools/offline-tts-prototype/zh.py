import pathlib,time,traceback,wave,json,numpy as np,socket
from piper import PiperVoice
root=pathlib.Path('/tmp/rtranslator-tts-audit');name='zh_CN-chaowen-medium'
def blocked(*a,**kw):raise RuntimeError('Network disabled during synthesis')
socket.socket.connect=blocked;socket.create_connection=blocked
try:
 t=time.perf_counter();v=PiperVoice.load(root/name/(name+'.onnx'),download_dir=root);load=time.perf_counter()-t;t=time.perf_counter();chunks=list(v.synthesize('你好，这是中文离线语音测试。翻译已经准备好了。'));elapsed=time.perf_counter()-t
 print('chunks',len(chunks))
 a=np.concatenate([c.audio_int16_array for c in chunks]);rate=chunks[0].sample_rate
 with wave.open(str(root/(name+'.wav')),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(rate);w.writeframes(a.tobytes())
 r={'model':name,'load_sec':load,'synthesis_sec':elapsed,'duration_sec':len(a)/rate,'rms':float(np.sqrt(np.mean(a.astype(float)**2))),'nonzero_samples':int(np.count_nonzero(a))};print(r);(root/'zh-result.json').write_text(json.dumps(r))
except Exception:traceback.print_exc()
