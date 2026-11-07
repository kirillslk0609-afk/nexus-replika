#!/usr/bin/env python3
"""Validate the delivered APK and create a source archive plus portable local Git repository."""
import hashlib,json,os,re,shutil,struct,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as E

ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'artifacts'
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest()
def main():
    apk=ART/'neuroscribe-debug.apk'
    android=json.loads((ART/'test-results/summary.json').read_text())
    assert android['android_tests_passed']==19
    # A presentation-only release can retain the expensive 0.4 engine evidence,
    # but must prove every non-UI source and native binary is unchanged and run
    # focused interactions + real ASR/diarization checks on the delivered APK.
    design=None
    if android['apk_sha256']!=digest(apk):
        design=json.loads((ART/'design/verification.json').read_text())
        assert design['apk_sha256']==digest(apk)
        assert design['baseline_apk_sha256']==android['apk_sha256']
        scope=json.loads((ART/'design/engine-unchanged.json').read_text())
        assert scope['apk_sha256']==android['apk_sha256']
        paths=list((ROOT/'app/src/main/java').rglob('*.kt'))+list((ROOT/'app/src/main/cpp').rglob('*'))
        actual={str(p.relative_to(ROOT)):digest(p) for p in paths if p.is_file() and p.name!='MainActivity.kt'}
        assert actual==scope['source'], 'Processing source changed: full engine regressions required'
        with zipfile.ZipFile(apk) as z:
            actual_native={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.startswith('lib/')}
        assert actual_native==scope['native'], 'Native binaries changed'
        passed=0
        for log in design['instrumentation_logs']:
            value=(ART/'design'/log).read_text()
            match=re.search(r'OK \((\d+) tests?\)',value)
            assert match and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)',value)
            passed+=int(match.group(1))
        assert passed>=5 and design['unique_android_tests_passed']==5
        assert design['full_transcript_cases_unchanged']==4
    speed=json.loads((ART/'test-results/speed-comparison.json').read_text())
    assert speed['apk_sha256']==android['apk_sha256']
    assert speed['unchanged_asr_cases']==45 and speed['unchanged_diarization_cases']==16
    assert speed['unchanged_regions']==654 and speed['unchanged_full_pipeline_transcripts']==7
    assert len(speed['decoder_paired'])==8 and all(x['bitExact'] for x in speed['decoder_paired'])
    assert speed['llm_paired']['allFinalTextsIdentical']
    assert (ART/'demo/neuroscribe-emulator-demo.mp4').stat().st_size>100000
    sdk=Path(os.environ.get('ANDROID_HOME',ROOT/'.tools/android-sdk'))
    env=os.environ.copy()
    if not env.get('JAVA_HOME'):
        candidates=list((ROOT/'.tools').glob('zulu*/Contents/Home'))
        if candidates:env['JAVA_HOME']=str(candidates[0])
    bt=sdk/'build-tools/35.0.0'
    signature=subprocess.check_output([str(bt/'apksigner'),'verify','--verbose',str(apk)],env=env,text=True)
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
    (ART/'apk-signature.txt').write_text(signature)
    subprocess.run([str(bt/'zipalign'),'-c','-P','16','4',str(apk)],check=True)
    badging=subprocess.check_output([str(bt/'aapt'),'dump','badging',str(apk)],text=True)
    assert 'android.permission.INTERNET' not in badging
    assert "native-code: 'arm64-v8a'" in badging
    manifest=json.loads((ROOT/'config/packaged-models.json').read_text())
    alignment={}
    with zipfile.ZipFile(apk) as z:
        assert all(not x.startswith('assets/') or x.startswith(('assets/models/', 'assets/licenses/')) for x in z.namelist()), 'Unexpected/test assets leaked into APK'
        for name,meta in manifest.items():
            h=hashlib.sha256()
            with z.open('assets/models/'+name) as f:
                for b in iter(lambda:f.read(1048576),b''):h.update(b)
            assert h.hexdigest()==meta['sha256'],name
        for name in z.namelist():
            if name.startswith('lib/') and name.endswith('.so'):
                b=z.read(name);offset=struct.unpack_from('<Q',b,32)[0];stride,count=struct.unpack_from('<HH',b,54)
                values=[]
                for i in range(count):
                    ph=struct.unpack_from('<IIQQQQQQ',b,offset+i*stride)
                    if ph[0]==1:
                        assert ph[-1]>=16384,name
                        values.append(hex(ph[-1]))
                alignment[Path(name).name]=values
    suites=[]
    for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        e=E.parse(p).getroot();suites.append({'suite':e.get('name'),**{k:int(e.get(k)) for k in ['tests','failures','errors','skipped']}})
    assert suites and all(s['failures']==s['errors']==s['skipped']==0 for s in suites)
    lint=E.parse(ROOT/'app/build/reports/lint-results-debug.xml')
    errors=len(lint.findall(".//issue[@severity='Error']"));warnings=len(lint.findall(".//issue[@severity='Warning']"))
    assert errors==0
    (ART/'unit-tests.json').write_text(json.dumps({'suites':suites},indent=2)+'\n')
    (ART/'apk-verification.json').write_text(json.dumps({'apk_bytes':apk.stat().st_size,'apk_sha256':digest(apk),
        'verified_model_files':list(manifest),'abi':'arm64-v8a','min_sdk':26,'internet_permission':False,
        'elf_load_alignment':alignment,'lint_errors':errors,'lint_warnings':warnings,
        'signature_v2_verified':'Verified using v2 scheme (APK Signature Scheme v2): true' in signature,
        'zipalign_16k_verified':True,'physical_android_test':'User-provided Samsung S10 video; see docs/PHONE_TEST.md; installed APK hash and quantitative metrics not verified',
        'engine_evidence_apk_sha256':android['apk_sha256'],
        'current_release_checks':'design/verification.json' if design else 'test-results/summary.json'},indent=2)+'\n')
    excluded={'.tools','.gradle','.kotlin','.git','build','.cxx','__pycache__','third_party'}
    files=[]
    for p in ROOT.rglob('*'):
        r=p.relative_to(ROOT)
        if not p.is_file() or excluded.intersection(r.parts):continue
        if p.name in {'local.properties','.DS_Store','SHA256SUMS','submission-commit.txt'} or p.suffix in {'.pyc','.apk','.zip','.keystore','.aar','.bundle'}:continue
        if r.as_posix().startswith('app/src/main/assets/models/'):continue
        if r.parts[0]=='artifacts' and (any(part.startswith(('presentation-', 'cover-iterations', 'phone-check-')) for part in r.parts) or p.name.startswith('SUBMISSION-BRIEF')):continue
        if r.parts[0]=='artifacts' and p.suffix not in {'.json','.png','.txt','.md','.csv','.log'}:continue
        files.append((p,r))
    archive=ART/'neuroscribe-source.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for p,r in files:z.write(p,Path('neuro-scribe')/r)
    # Isolated repository: never stage or change the user's parent workspace repository.
    repo=ROOT/'.tools/submission-repository'
    if repo.exists():shutil.rmtree(repo)
    repo.mkdir(parents=True)
    for p,r in files:
        dest=repo/r;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,dest)
    def git(*args):return subprocess.check_output(['git','-C',str(repo),*args],text=True)
    git('init','-b','codex/submission')
    git('add','.')
    git('-c','user.name=NeuroScribe contributors','-c','user.email=neuroscribe@localhost','commit','-m','Prepare offline Android hackathon submission')
    bundle=ART/'neuroscribe-git.bundle'
    git('bundle','create',str(bundle),'--all')
    commit=git('rev-parse','HEAD').strip()
    (ART/'submission-commit.txt').write_text(commit+'\n')
    outputs=[apk,archive,bundle]
    demo=ART/'demo/neuroscribe-emulator-demo.mp4'
    if demo.exists():outputs.append(demo)
    (ART/'SHA256SUMS').write_text(''.join(f'{digest(p)}  {p.relative_to(ART)}\n' for p in outputs))
    print(f'Validated {sum(s["tests"] for s in suites)} JVM tests; APK {apk.stat().st_size} bytes; local Git commit {commit}')
if __name__=='__main__':main()
