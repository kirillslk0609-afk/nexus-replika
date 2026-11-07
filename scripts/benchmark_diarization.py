#!/usr/bin/env python3
"""LEGACY sherpa global clustering comparison (not the app 0.2 pipeline). Host-only: pip install sherpa-onnx==2.0.1 numpy. Not phone speed or DER."""
import argparse,json,subprocess,time,wave,sys
from pathlib import Path
import numpy as np
import sherpa_onnx as s

ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser()
    p.add_argument('--embedding',type=Path,default=ROOT/'app/src/main/assets/models/embedding.onnx')
    p.add_argument('--thresholds',type=float,nargs='+',default=[0.5,0.7])
    p.add_argument('--output',type=Path,default=ROOT/'artifacts/diarization-reproduction.json')
    args=p.parse_args();assets=ROOT/'app/src/androidTest/assets';work=ROOT/'.tools/quality';work.mkdir(parents=True,exist_ok=True)
    cases=[('single_ru','russian.wav',1),('two_en','two-speakers.wav',2),('synthetic_two','synthetic-two.wav',2),('four_zh','four-speakers.wav',4),('real_ru_2','conversation.mp3',2),('real_ru_14','conversation-second.mp3',2)]
    inputs={}
    for label,name,expected in cases:
        path=assets/name
        if path.suffix=='.mp3':
            target=work/(label+'.wav')
            if not target.exists():
                command=['afconvert','-f','WAVE','-d','LEI16@16000',str(path),str(target)] if sys.platform=='darwin' else ['ffmpeg','-y','-i',str(path),'-ar','16000','-c:a','pcm_s16le',str(target)]
                subprocess.run(command,check=True)
            path=target
        with wave.open(str(path)) as w:
            assert w.getframerate()==16000 and w.getsampwidth()==2
            pcm=np.frombuffer(w.readframes(w.getnframes()),dtype=np.int16).astype(np.float32).reshape(-1,w.getnchannels()).mean(axis=1)/32768
        inputs[label]=pcm
    rows=[]
    for threshold in args.thresholds:
        model=s.OfflineSpeakerDiarization(s.OfflineSpeakerDiarizationConfig(
            segmentation=s.OfflineSpeakerSegmentationModelConfig(pyannote=s.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(ROOT/'app/src/main/assets/models/segmentation.onnx')),num_threads=4),
            embedding=s.SpeakerEmbeddingExtractorConfig(model=str(args.embedding),num_threads=4),
            clustering=s.FastClusteringConfig(num_clusters=-1,threshold=threshold),min_duration_on=.15,min_duration_off=.3))
        for label,_,expected in cases:
            pcm=inputs[label];start=time.monotonic();regions=model.process(pcm).sort_by_start_time()
            row={'model':args.embedding.name,'threshold':threshold,'fixture':label,'expected_speakers':expected,
                'detected_speakers':len({r.speaker for r in regions}),'elapsed_seconds':time.monotonic()-start,
                'segments':[{'speaker':r.speaker,'start':r.start,'end':r.end} for r in regions]}
            rows.append(row);print(label,threshold,row['detected_speakers'],flush=True)
    args.output.write_text(json.dumps({'environment':'host; speaker count is not DER','results':rows},indent=2)+'\n')
if __name__=='__main__':main()
