#!/usr/bin/env python3
"""Run the in-game feature checks (SelfTest.java) on a real dev server, per Minecraft version.

    tools/selftest.py            every version in versions/
    tools/selftest.py 26.1 26.3  just these

Boots each version's server on a fresh world. The server runs the checks once it has started, writes
selftest-result.txt and stops. Exits non-zero if any check failed or a server never got that far.
"""
import os, pathlib, shutil, signal, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TIMEOUT = 900


def run(version):
    rundir = ROOT / 'versions' / version / 'runs' / 'selftest'
    result = rundir / 'selftest-result.txt'
    shutil.rmtree(rundir / 'world', ignore_errors=True)
    rundir.mkdir(parents=True, exist_ok=True)
    result.unlink(missing_ok=True)
    (rundir / 'eula.txt').write_text('eula=true\n')
    # Offline mode: the fake players' names must resolve without asking Mojang.
    (rundir / 'server.properties').write_text('online-mode=false\nserver-port=25598\nmax-tick-time=-1\n')

    env = dict(os.environ)
    env.setdefault('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk')
    log = rundir / 'selftest.log'
    with open(log, 'w') as out:
        server = subprocess.Popen([str(ROOT / 'gradlew'), f':{version}:runSelftest', '--console=plain'],
                                  cwd=ROOT, env=env, stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            server.wait(TIMEOUT)
        except subprocess.TimeoutExpired:
            os.killpg(server.pid, signal.SIGKILL)
            return f'server still running after {TIMEOUT}s, see {log}'
    if not result.exists():
        return f'server stopped without running the checks, see {log}'
    lines = result.read_text().splitlines()
    print('\n'.join('  ' + line for line in lines))
    return None if lines and lines[-1].endswith(' 0 failed') else 'checks failed'


versions = sys.argv[1:] or sorted(p.name for p in (ROOT / 'versions').iterdir() if (p / 'gradle.properties').exists())
problems = {}
for version in versions:
    print(f'== {version}', flush=True)
    problem = run(version)
    if problem:
        problems[version] = problem
        print(f'  {problem}')
print('\n' + ('ALL PASSED' if not problems else 'FAILED: ' + ', '.join(problems)))
sys.exit(1 if problems else 0)
