#!/usr/bin/env python3
"""Speaker-count and clean-channel agreement audit. The channel proxy is NOT manual DER."""
import argparse,itertools,json,subprocess,sys,wave
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[1]

def channel_agreement(regions,path):
    with wave.open(str(path)) as w:
        assert w.getnchannels()==2 and w.getsampwidth()==2
        rate=w.getframerate();samples=np.frombuffer(w.readframes(w.getnframes()),np.int16).astype(np.float32).reshape(-1,2)/32768
    block=int(rate*.02);frames=samples[:len(samples)//block*block].reshape(-1,block,2)
    rms=np.sqrt((frames*frames).mean(axis=1))
    # Only loud, strongly isolated single-channel frames. This is an explicitly limited proxy.
    mask=(rms.max(axis=1)>.01)&(rms.max(axis=1)>4*rms.min(axis=1));reference=rms.argmax(axis=1)
    prediction=np.full(len(frames),-1,int);overlap=np.zeros(len(frames),bool)
    for r in regions:
        a=max(0,round(r['start']/.02));b=min(len(frames),round(r['end']/.02))
        overlap[a:b]|=prediction[a:b]>=0
        prediction[a:b]=r['speaker']
    # Predicted overlaps cannot receive an undeserved correct single-speaker match.
    prediction[overlap]=-1
    ids=set(prediction[prediction>=0]);best=0
    if len(ids)==1:
        only=next(iter(ids));best=max(int(np.count_nonzero(mask&(reference==c)&(prediction==only))) for c in [0,1])
    for left,right in itertools.permutations(ids,2):
        best=max(best,int(np.count_nonzero(mask&(((reference==0)&(prediction==left))|((reference==1)&(prediction==right))))))
    return {'clean_channel_seconds':round(float(mask.sum())*.02,2),
        'best_one_to_one_speaker_agreement':round(best/max(int(mask.sum()),1),4),
        'manual_der':False,'rule':'20 ms RMS > 0.01 and > 4x other channel; overlap predictions count as errors'}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,default=ROOT/'artifacts/test-results/diarization-regression.json');parser.add_argument('--output',type=Path,default=ROOT/'artifacts/test-results/diarization-audit.json');args=parser.parse_args()
    data=json.loads(args.report.read_text());work=ROOT/'.tools/quality/channel-audit';work.mkdir(parents=True,exist_ok=True)
    rows=[]
    for case in data:
        row={k:v for k,v in case.items() if k!='regions'};row['speaker_count_correct']=case['expectedSpeakers']==case['detectedSpeakers']
        if case['fixture'].endswith('.mp3'):
            source=ROOT/'app/src/androidTest/assets'/case['fixture'];wav=work/(source.stem+'.wav')
            if not wav.exists():
                command=['afconvert','-f','WAVE','-d','LEI16',str(source),str(wav)] if sys.platform=='darwin' else ['ffmpeg','-y','-i',str(source),'-c:a','pcm_s16le',str(wav)]
                subprocess.run(command,check=True)
            row['channel_proxy']=channel_agreement(case['regions'],wav)
        rows.append(row)
    report={'scope':'Android diarization on local mono audio; original stereo channels used only by this external evaluation tool',
        'manual_DER_available':False,'correct_speaker_count_cases':sum(r['speaker_count_correct'] for r in rows),'total_cases':len(rows),'results':rows}
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
