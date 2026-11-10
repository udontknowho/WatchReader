#!/usr/bin/env python3
"""受限 JVM 在线验收。导入模块不读候选文件、不创建目录、不联网。"""
import argparse
import collections
import concurrent.futures
import json
import math
import os
import pathlib
import shutil
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def positive(value):
    value = float(value)
    if not math.isfinite(value) or value <= 0:
        raise argparse.ArgumentTypeError('必须是大于零的有限数值')
    return value


def arguments(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidates', type=pathlib.Path, required=True, help='任意 Legado 候选 JSON 数组')
    p.add_argument('--out', type=pathlib.Path, required=True, help='本轮独立输出目录，必须不存在或为空')
    p.add_argument('--jar', type=pathlib.Path, default=ROOT / 'build/test.jar')
    p.add_argument('--classpath', help='完整 JVM 类路径，覆盖 --jar 和默认依赖')
    p.add_argument('--java', default=shutil.which('java') or 'java')
    p.add_argument('--workers', type=int, choices=range(1, 7), default=2, metavar='1..6')
    p.add_argument('--first-timeout', type=positive, default=30, help='首轮单源秒数')
    p.add_argument('--retry-timeout', type=positive, default=60, help='复测单源秒数')
    p.add_argument('--total-timeout', type=positive, default=1200, help='全轮墙钟秒数')
    p.add_argument('--no-retry', action='store_true')
    p.add_argument('--previous', type=pathlib.Path, help='可选的旧通过源数组，仅用于复测排序')
    return p.parse_args(argv)


def complete(s):
    return isinstance(s, dict) and s.get('bookSourceType', 0) == 0 and all(
        isinstance(s.get(k), dict) and s[k] for k in ('ruleSearch', 'ruleToc', 'ruleContent')) and bool(
        s.get('searchUrl')) and bool(s.get('bookSourceUrl'))


def run(args):
    candidates = args.candidates.resolve()
    sources = json.loads(candidates.read_text())
    if not isinstance(sources, list):
        raise ValueError('候选 JSON 必须是数组')
    previous = json.loads(args.previous.read_text()) if args.previous else []
    oldurls = {s.get('bookSourceUrl', '').rstrip('/') for s in previous if isinstance(s, dict)}
    if args.classpath:
        cp = args.classpath
    else:
        prefix = pathlib.Path(os.environ.get('PREFIX', '/data/data/com.termux/files/usr'))
        jars = [args.jar.resolve(), ROOT / 'libs/rhino-slim.jar', prefix / 'opt/kotlin/lib/kotlin-stdlib.jar']
        for jar in jars:
            if not jar.is_file():
                raise FileNotFoundError(f'依赖不存在：{jar}，可用 --classpath 指定')
        cp = os.pathsep.join(map(str, jars))
    out = args.out.resolve()
    if out.exists() and any(out.iterdir()):
        raise ValueError(f'输出目录非空，拒绝覆盖已有证据：{out}')
    out.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    deadline = start + args.total_timeout
    results = {}
    lock = threading.Lock()

    def save():
        summary = {'total': len(sources), 'elapsedSeconds': round(time.monotonic() - start, 1),
                   'latestCounts': dict(collections.Counter(x['status'] for x in results.values())),
                   'measured': sum(v.get('round', 0) > 0 for v in results.values()),
                   'candidates': str(candidates), 'workers': args.workers}
        (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
        passed = [sources[i] for i, r in sorted(results.items()) if r['status'] == 'passed']
        (out / 'passed-all.json').write_text(json.dumps(passed, ensure_ascii=False, indent=2))
        unique = {}
        for s in passed:
            unique.setdefault(s['bookSourceUrl'].rstrip('/'), s)
        (out / 'passed.json').write_text(json.dumps(list(unique.values()), ensure_ascii=False, indent=2))
        (out / 'latest-results.json').write_text(json.dumps(list(results.values()), ensure_ascii=False, indent=2))
        return summary

    def test(i, budget, round_no):
        left = deadline - time.monotonic()
        if left <= 0:
            return
        source = sources[i]
        began = time.monotonic()
        cmd = [args.java, '-Xmx384m', '-Xms32m', '-XX:+UseSerialGC', '-XX:MaxMetaspaceSize=160m',
               '-XX:ReservedCodeCacheSize=32m', '-cp', cp, 'SourceAcceptanceKt', str(candidates), str(i)]
        actual_budget = min(budget, left)
        log = ''
        try:
            p = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, errors='replace', timeout=actual_budget)
            log = p.stdout
            lines = [x[len('ACCEPTANCE '):] for x in log.splitlines() if x.startswith('ACCEPTANCE ')]
            result = json.loads(lines[-1]) if lines and p.returncode == 0 else {
                'status': 'process_error', 'detail': log[-1500:], 'exitCode': p.returncode}
        except subprocess.TimeoutExpired as e:
            log = e.stdout or b''
            if isinstance(log, bytes):
                log = log.decode(errors='replace')
            result = {'status': 'budget_timeout', 'detail': f'本轮墙钟预算 {actual_budget:.2f} 秒，不判定永久失效'}
        except (OSError, ValueError, TypeError) as e:
            result = {'status': 'process_error', 'detail': f'{type(e).__name__}:{e}'}
        result.update(index=i, name=source.get('bookSourceName'), url=source.get('bookSourceUrl'),
                      round=round_no, seconds=round(time.monotonic() - began, 2))
        with lock:
            (out / f'probe-{i}-round-{round_no}.log').write_text(log)
            results[i] = result
            with (out / 'results.jsonl').open('a') as f:
                f.write(json.dumps(result, ensure_ascii=False) + '\n')
            save()

    def batch(indices, budget, round_no):
        # 最多 workers 个在飞任务，不一次性排队整个候选库。
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as ex:
            iterator = iter(indices)
            running = set()
            while time.monotonic() < deadline:
                while len(running) < args.workers:
                    i = next(iterator, None)
                    if i is None:
                        break
                    running.add(ex.submit(test, i, budget, round_no))
                if not running:
                    break
                done, running = concurrent.futures.wait(running, return_when=concurrent.futures.FIRST_COMPLETED)
                for future in done:
                    future.result()

    todo = []
    for i, source in enumerate(sources):
        if complete(source):
            todo.append(i)
        else:
            results[i] = {'index': i, 'status': 'skipped_nontext_or_incomplete', 'round': 0}
    print('结构完整文字源', len(todo), '跳过', len(sources) - len(todo), flush=True)
    save()
    batch(todo, args.first_timeout, 1)
    print('首轮', json.dumps(save(), ensure_ascii=False), flush=True)
    if not args.no_retry:
        retry = [i for i in todo if results.get(i, {}).get('status') != 'passed']
        retry.sort(key=lambda i: (sources[i]['bookSourceUrl'].rstrip('/') not in oldurls,
                                 results.get(i, {}).get('status') not in ('budget_timeout', 'inconclusive_timeout')))
        batch(retry, args.retry_timeout, 2)
    for i in todo:
        results.setdefault(i, {'index': i, 'name': sources[i].get('bookSourceName'),
                               'status': 'unmeasured_global_budget', 'round': 0})
    print('最终', json.dumps(save(), ensure_ascii=False), flush=True)


if __name__ == '__main__':
    run(arguments())
