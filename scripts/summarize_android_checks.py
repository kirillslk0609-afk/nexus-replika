#!/usr/bin/env python3
"""Collect completed Android measurements and distinguish quality from functional checks."""
import argparse,csv,hashlib,importlib.util,json,re,subprocess
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('serial',nargs='?',default='emulator-5566');parser.add_argument('--rerun',type=Path);parser.add_argument('--rerun-reason',default='');args=parser.parse_args()
    log=(ROOT/'artifacts/android-instrumentation.txt').read_text()
    def outcomes(text):
        found={};fields={}
        for line in text.splitlines():
            if line.startswith('INSTRUMENTATION_STATUS: '):
                key,value=line[len('INSTRUMENTATION_STATUS: '):].split('=',1);fields[key]=value
            elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
                code=int(line.split(': ',1)[1])
                if code!=1 and 'class' in fields and 'test' in fields:found[fields['class']+'#'+fields['test']]=code
                fields={}
        return found
    results=outcomes(log);initial_failures=sum(code!=0 for code in results.values())
    run_files=['android-instrumentation.txt']
    if args.rerun:
        assert args.rerun_reason,'Explain the targeted rerun in the report'
        retry_log=args.rerun.read_text();assert re.search(r'OK \([0-9]+ tests?\)',retry_log),'Targeted rerun did not pass'
        retries=outcomes(retry_log);assert retries and set(retries)<=set(results),'Unexpected retry tests'
        results.update(retries);run_files.append(args.rerun.name)
    expected=int(re.search(r'INSTRUMENTATION_STATUS: numtests=(\d+)',log).group(1))
    assert len(results)==expected and all(code==0 for code in results.values()),results
    adb=[str(ROOT/'.tools/android-sdk/platform-tools/adb'),'-s',args.serial]
    release=subprocess.check_output(adb+['shell','getprop','ro.build.version.release'],text=True).strip()
    model=subprocess.check_output(adb+['shell','getprop','ro.product.model'],text=True).strip()
    emulator=args.serial.startswith('emulator-')
    device=f'Android {release} {model} ({args.serial})'
    folder=ROOT/'artifacts/test-results';folder.mkdir(exist_ok=True)
    names=['speed-decoder','speed-punctuation','speed-pipeline','asr-noise-regression','diarization-regression','test-russian','test-two-speakers','test-silence','test-cold-prepare','quality-russian-noise.wav','quality-russian-pauses.wav','quality-conversation.mp3','quality-conversation-second.mp3']
    spec=importlib.util.spec_from_file_location('evaluate',ROOT/'scripts/evaluate.py');evaluate=importlib.util.module_from_spec(spec);spec.loader.exec_module(evaluate)
    reference=(ROOT/'samples/russian-reference.txt').read_text()
    rows=[]
    for name in names:
        data=json.loads(subprocess.check_output(adb+['exec-out','run-as','ru.neuroscribe.app','cat','files/'+name+'.json']))
        (folder/(name+'.json')).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
        if not isinstance(data,dict) or 'result' not in data:continue
        turns=data['result']['transcript'];expected={'test-russian':1,'test-two-speakers':2,'test-silence':0,'quality-russian-noise.wav':1,'quality-russian-pauses.wav':1,'quality-conversation.mp3':2,'quality-conversation-second.mp3':2}[name]
        row={'fixture':name,'device':device,'duration_s':data['durationMs']/1000,'processing_s':data['processingMs']/1000,'rtf':round(data['processingMs']/data['durationMs'],3),'speakers_expected':expected,'speakers_detected':len({t['speaker_id'] for t in turns}),'turns':len(turns),'llm_calls':data['llmCalls'],'rejected_llm_edits':data['rejectedEdits'],'wer':''}
        if name in ['test-russian','quality-russian-noise.wav','quality-russian-pauses.wav']:
            ref=reference+' '+reference if 'pauses' in name else reference
            row['wer']=round(evaluate.wer(ref,' '.join(t['text'] for t in turns))['wer'],6)
        rows.append(row)
    with (folder/'results.csv').open('w') as f:
        w=csv.DictWriter(f,fieldnames=rows[0].keys());w.writeheader();w.writerows(rows)
    hasher=hashlib.sha256()
    with (ROOT/'artifacts/neuroscribe-debug.apk').open('rb') as f:
        for block in iter(lambda:f.read(1048576),b''):hasher.update(block)
    digest=hasher.hexdigest()
    installed_path=subprocess.check_output(adb+['shell','pm','path','ru.neuroscribe.app'],text=True).strip().removeprefix('package:')
    installed_digest=subprocess.check_output(adb+['shell','sha256sum',installed_path],text=True).split()[0]
    assert digest==installed_digest,'Installed APK differs from the artifact'
    report={'collected_utc':datetime.now(timezone.utc).isoformat(),'apk_sha256':digest,'serial':args.serial,'android_tests_passed':len(results),'android_run_logs':run_files,'initial_run_failures':initial_failures,'targeted_rerun_reason':args.rerun_reason,'final_test_statuses':results,'processing_includes_model_preparation':True,'physical_phone':not emulator,'measurements':rows}
    (folder/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
