#!/usr/bin/env python3
"""Collect focused 0.6.1 evidence without relabelling the full 0.4 regression run."""
import hashlib,json,re,subprocess,sys,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'artifacts/design'
adb=[str(ROOT/'.tools/android-sdk/platform-tools/adb'),'-s',sys.argv[1] if len(sys.argv)>1 else 'emulator-5566']
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def read_file(name):return subprocess.check_output(adb+['exec-out','run-as','ru.neuroscribe.app','cat','files/'+name])
log=(ART/'android-instrumentation.txt').read_text()
assert 'OK (5 tests)' in log
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)',log)
for name in ['empty','ready','error','processing','result','two-speakers']:
 (ART/(name+'.png')).write_bytes(read_file('design-'+name+'.png'))
for remote,local in [('design-ui-result.json','ui-result.json'),('test-two-speakers.json','two-speakers-result.json'),('quality-russian-noise.wav.json','noisy-result.json'),('quality-russian-pauses.wav.json','pauses-result.json')]:
 (ART/local).write_bytes(read_file(remote))
pairs=[('ui-result.json','test-russian.json'),('two-speakers-result.json','test-two-speakers.json'),('noisy-result.json','quality-russian-noise.wav.json'),('pauses-result.json','quality-russian-pauses.wav.json')]
for current,previous in pairs:
 a=json.loads((ART/current).read_text());b=json.loads((ROOT/'artifacts/test-results'/previous).read_text())['result']
 assert a.get('result',a)==b,current
scope=json.loads((ART/'engine-unchanged.json').read_text())
paths=list((ROOT/'app/src/main/java').rglob('*.kt'))+list((ROOT/'app/src/main/cpp').rglob('*'))
actual={str(p.relative_to(ROOT)):digest(p) for p in paths if p.is_file() and p.name!='MainActivity.kt'}
assert actual==scope['source']
apk=ROOT/'artifacts/neuroscribe-debug.apk'
with zipfile.ZipFile(apk) as z:
 native={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('lib/')}
assert native==scope['native']
installed_path=subprocess.check_output(adb+['shell','pm','path','ru.neuroscribe.app'],text=True).strip().removeprefix('package:')
installed_sha=subprocess.check_output(adb+['shell','sha256sum',installed_path],text=True).split()[0]
assert installed_sha==digest(apk)
assert subprocess.check_output(adb+['shell','settings','get','global','airplane_mode_on'],text=True).strip()=='1'
report={'version':'0.6.1','apk_sha256':installed_sha,'baseline_apk_sha256':scope['apk_sha256'],
 'device':'Android 15 ARM64 emulator-5566; airplane mode','physical_phone_test':False,
 'instrumentation_logs':['android-instrumentation.txt'],'unique_android_tests_passed':5,
 'full_transcript_cases_unchanged':4,'compared_cases':[p[0] for p in pairs],
 'non_ui_sources_unchanged':len(actual),'native_binaries_unchanged':len(native),
 'full_19_test_engine_regression':'Retained from 0.4; not rerun on this APK'}
(ART/'verification.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
