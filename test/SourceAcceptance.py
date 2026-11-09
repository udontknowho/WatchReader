#!/usr/bin/env python3
"""最多六个受限 JVM，逐源证据落盘。全轮 20 分钟，首轮 14 秒、复测 24 秒。"""
import concurrent.futures, collections, json, os, pathlib, subprocess, threading, time
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / '.fj-tmp/source-integration'
ORIGINAL = next(ROOT.glob('墨辰*7.5*.json'))
SOURCES = json.loads(ORIGINAL.read_text())
CP = ':'.join(map(str, [OUT/'core.jar', ROOT/'libs/rhino-slim.jar', pathlib.Path(os.environ['PREFIX'])/'opt/kotlin/lib/kotlin-stdlib.jar']))
JAVA = str(pathlib.Path(os.environ['PREFIX'])/'bin/java')
START = time.monotonic()
DEADLINE = START + 1200
LOCK = threading.Lock()
RESULTS = {}
def save():
    counts = collections.Counter(x['status'] for x in RESULTS.values())
    summary = {'total': len(SOURCES), 'elapsedSeconds': round(time.monotonic()-START, 1), 'latestCounts': dict(counts), 'measured': sum(v.get('round',0)>0 for v in RESULTS.values())}
    (OUT/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    passed = [SOURCES[i] for i,r in sorted(RESULTS.items()) if r['status']=='passed']
    (OUT/'passed-all.json').write_text(json.dumps(passed, ensure_ascii=False, indent=2))
    unique = {}
    for s in passed: unique.setdefault(s['bookSourceUrl'].rstrip('/'), s)
    (OUT/'passed.json').write_text(json.dumps(list(unique.values()), ensure_ascii=False, indent=2))
    return summary

def test(i, budget, round_no):
    if time.monotonic() > DEADLINE-2: return
    source = SOURCES[i]
    t = time.monotonic()
    cmd = [JAVA, '-Xmx384m','-Xms32m','-XX:+UseSerialGC','-XX:MaxMetaspaceSize=160m','-XX:ReservedCodeCacheSize=32m', '-cp', CP, 'SourceAcceptanceKt', str(ORIGINAL), str(i)]
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=min(budget, DEADLINE-time.monotonic()))
        log = p.stdout
        lines = [x[len('ACCEPTANCE '):] for x in log.splitlines() if x.startswith('ACCEPTANCE ')]
        result = json.loads(lines[-1]) if lines else {'status':'process_error', 'detail':log[-1500:]}
    except subprocess.TimeoutExpired as e:
        log = e.stdout or b''
        if isinstance(log,bytes): log=log.decode(errors='replace')
        result = {'status':'budget_timeout', 'detail':f'单源墙钟预算 {budget} 秒，不能判定永久失效'}
    result.update(index=i, name=source.get('bookSourceName'), url=source.get('bookSourceUrl'), round=round_no, seconds=round(time.monotonic()-t,2))
    with LOCK:
        (OUT/f'probe-{i}.log').write_text(log)
        RESULTS[i]=result
        with (OUT/'results.jsonl').open('a') as f: f.write(json.dumps(result,ensure_ascii=False)+'\n')
        save()

def complete(s):
    return s.get('bookSourceType',0)==0 and all(isinstance(s.get(k),dict) and s[k] for k in ('ruleSearch','ruleToc','ruleContent')) and bool(s.get('searchUrl')) and bool(s.get('bookSourceUrl'))

if __name__=='__main__':
    todo=[]
    for i,s in enumerate(SOURCES):
        if complete(s): todo.append(i)
        else: RESULTS[i]={'index':i,'name':s.get('bookSourceName'),'status':'skipped_nontext_or_incomplete','round':0}
    save()
    print('结构完整文字源',len(todo),'跳过',len(SOURCES)-len(todo),flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        list(ex.map(lambda i:test(i,14,1),todo))
    print('首轮',json.dumps(save(),ensure_ascii=False),flush=True)
    # 优先复测曾通过源，其次预算超时和其余非通过项；语法不支持不当作站点死亡。
    old=json.loads((OUT/'backup/legado.json').read_text())
    oldurls={s['bookSourceUrl'].rstrip('/') for s in old}
    retry=[i for i in todo if RESULTS.get(i,{}).get('status')!='passed']
    retry.sort(key=lambda i:(SOURCES[i]['bookSourceUrl'].rstrip('/') not in oldurls,RESULTS.get(i,{}).get('status')!='budget_timeout'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        list(ex.map(lambda i:test(i,24,2),retry))
    for i in todo:
        RESULTS.setdefault(i,{'index':i,'name':SOURCES[i].get('bookSourceName'),'status':'unmeasured_global_budget','round':0})
    (OUT/'latest-results.json').write_text(json.dumps(list(RESULTS.values()),ensure_ascii=False,indent=2))
    print('最终',json.dumps(save(),ensure_ascii=False),flush=True)
