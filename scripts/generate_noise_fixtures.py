#!/usr/bin/env python3
"""Recreate ASR controls: numpy required; run from the project root."""
import json,wave
from pathlib import Path
import numpy as np
p=Path(__file__).resolve().parents[1]
base=json.loads((p/'config/asr-base-baseline.json').read_text())
cases=json.loads((p/'samples/noise-sources/references.json').read_text())
rows=[]
fixtures=Path('app/src/androidTest/assets/noise');fixtures.mkdir(exist_ok=True)
for case in cases:
 lang=case['language']
 with wave.open(case['file']) as w:pcm=np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(np.float32)/32768
 for kind,snr in [('clean',None),('white',12),('white',6),('pink',6),('hum',6)]:
  rng=np.random.default_rng(20261107);n=rng.normal(size=len(pcm));t=np.arange(len(pcm))/16000
  if kind=='pink':
   spec=np.fft.rfft(n);spec/=np.sqrt(np.maximum(np.fft.rfftfreq(len(n),1/16000),40));n=np.fft.irfft(spec,n=len(n))
  if kind=='hum':n=np.sin(2*np.pi*100*t)+.5*np.sin(2*np.pi*200*t)+.15*n
  audio=pcm.copy() if snr is None else pcm+n*(np.sqrt(np.mean(pcm**2))/np.sqrt(np.mean(n**2)))*10**(-snr/20)
  if case['name']=='original' and kind=='white' and snr==12:
   with wave.open('app/src/androidTest/assets/russian-noise.wav') as w:audio=np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(np.float32)/32768
  audio=np.clip(audio,-1,1).astype(np.float32)[640:]
  # WAV float32 retains exactly the same samples used by the old recognizer (no requantization).
  name=case['name']+'-'+kind+(str(snr) if snr else '')+'.wav'
  import struct
  data=audio.astype('<f4').tobytes();(fixtures/name).write_bytes(b'RIFF'+struct.pack('<I',36+len(data))+b'WAVEfmt '+struct.pack('<IHHIIHH',16,3,1,16000,64000,4,32)+b'data'+struct.pack('<I',len(data))+data)
  row=dict(name=case['name'],language=lang,noise=kind,snr_db=snr,file='noise/'+name,reference=case['reference'])
  row['baseline']=next(x['raw'] for x in base if x['name']==case['name'] and x['noise']==kind and x['snr_db']==snr)
  rows.append(row)
(fixtures/'reference.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
print(f'Generated {len(rows)} noise fixtures')
