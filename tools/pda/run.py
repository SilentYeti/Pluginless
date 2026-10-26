#!/usr/bin/env python3
"""Disposable 1.21.1 NeoForge PDA client/server smoke run; no feature sources are modified."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import tempfile
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def cached(group, artifact, version):
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    candidates = list((cache / group / artifact / version).glob(f'*/{artifact}-{version}' + ('-all.jar' if artifact.startswith('ldlib2-') else '.jar')))
    if not candidates:
        raise RuntimeError(f'Build WFLib/Pluginless first: missing cached {artifact}:{version}')
    return max(candidates, key=lambda p: p.stat().st_mtime)


def wait_for(path, processes, timeout=600):
    deadline = time.monotonic() + timeout
    while not path.exists():
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f'Process {process.pid} exited ({process.returncode}) before {path.name}; see run logs')
        if time.monotonic() >= deadline:
            raise TimeoutError(f'Timed out waiting for {path}')
        time.sleep(0.2)
    return json.loads(path.read_text())


def write_action(control, number, **action):
    temporary = control / 'action.tmp'
    temporary.write_text(json.dumps(dict(id=number, **action)))
    temporary.replace(control / 'action.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pda-theme', help='Override PDA theme in the disposable client JVM')
    parser.add_argument('--curios-jar', type=Path, help='Optional Curios1.21.1 jar loaded in both disposable games')
    parser.add_argument('--curios-slots', action='store_true', help='Enable charm+pda slots in disposable world only')
    parser.add_argument('--wflib', type=Path, default=ROOT.parent / 'WFLib-main')
    parser.add_argument('--apps', nargs='*', default=['wflib:terminal'], help='PDA IDs to open; missing/invisible apps fail')
    parser.add_argument('--ranks', type=Path, help='ranks.json to start the server with (default: the mod\'s own)')
    parser.add_argument('--stocks', type=Path, help='stocks.json fixture for the disposable server')
    parser.add_argument('--long-text', action='store_true', help='Seed legal long company/home names for text audits')
    parser.add_argument('--script', type=Path, action='append', default=[], help='JSON action array; repeat to run page scenarios in one instance')
    parser.add_argument('--skip-build', action='store_true', help='Use existing jars; manifest still records exact bytes')
    parser.add_argument('--show', action='store_true', help='Open on the desktop and keep running until Minecraft quits')
    parser.add_argument('--pluginless-jar', type=Path, help='Use a built Pluginless jar from a page worker checkout')
    args = parser.parse_args()
    for executable in ('java', 'xdotool', *(() if args.show else ('Xvfb',))):
        if not shutil.which(executable):
            parser.error(f'{executable} is required')
    wflib = args.wflib.resolve()
    if args.show and not os.environ.get('DISPLAY'):
        parser.error('--show requires the desktop DISPLAY (X11 or XWayland)')
    env = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-21-openjdk')
    if not args.show:
        env['LIBGL_ALWAYS_SOFTWARE'] = 'true'
    steps_from_file = []
    for script in args.script:
        steps = json.loads(script.read_text())
        if not isinstance(steps, list):
            parser.error(f'{script} must contain a JSON array')
        steps_from_file.extend(steps)
    if not args.skip_build:
        for project, task in ((wflib, 'jar'), (ROOT, ':1.21.1:jar')):
            if project == ROOT and args.pluginless_jar:
                continue
            subprocess.run(['sh', str(project / 'gradlew'), task, '--console=plain', '--no-daemon'], cwd=project, env=env, check=True)
    jars = {
        'pluginlessJar': args.pluginless_jar.resolve() if args.pluginless_jar else ROOT / 'versions/1.21.1/build/libs/pluginless-0.1.0+1.21.1.jar',
        'wflibJar': wflib / 'build/libs/wflib-1.8.36.jar',
        'ahfJar': cached('com.norwood', 'ahf-1.21.1-neoforge', '1.0.8-SNAPSHOT'),
        'ldlibJar': cached('com.lowdragmc.ldlib2', 'ldlib2-neoforge-1.21.1', '2.2.26'),
        'gemrenderJar': cached('com.wf.gemrender', 'gemrender-1.21.1', '0.1.17-SNAPSHOT'),
        'flywheelJar': cached('dev.engine-room.flywheel', 'flywheel-neoforge-1.21.1', '1.0.6'),
    }
    if args.curios_jar:
        jars['curiosJar'] = args.curios_jar.resolve()
    if args.curios_slots and not args.curios_jar:
        parser.error('--curios-slots requires --curios-jar')
    runs = ROOT / 'build/pda-harness'
    runs.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='run-', dir=runs))
    print(f'PDA harness artifacts: {output}', flush=True)
    control = output / 'control'
    control.mkdir()
    (output / 'jars').mkdir()
    manifest = {}
    # A page worker may rebuild their original jar while this client/server pair is running.
    for name, path in jars.items():
        data = path.read_bytes()
        (output / 'jars' / name).mkdir()
        snapshot = output / 'jars' / name / path.name
        snapshot.write_bytes(data)
        manifest[name] = {'source': str(path), 'path': str(snapshot), 'sha256': hashlib.sha256(data).hexdigest()}
        jars[name] = snapshot
    (output / 'jars.json').write_text(json.dumps(manifest, indent=2))
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    processes, logs = [], []

    def spawn(command, directory, log):
        stream = (output / log).open('w')
        logs.append(stream)
        process = subprocess.Popen(command, cwd=directory, env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        processes.append(process)
        return process

    def start_game(mode):
        project = output / (mode + '-project')
        project.mkdir()
        for name in ('build.gradle', 'settings.gradle'):
            shutil.copy2(HERE / name, project / name)
        shutil.copytree(HERE / 'src', project / 'src')
        game = output / mode
        game.mkdir()
        if mode == 'server':
            (game / 'eula.txt').write_text('eula=true\n')
            if args.ranks:
                (game / 'config' / 'pluginless').mkdir(parents=True)
                shutil.copy2(args.ranks, game / 'config' / 'pluginless' / 'ranks.json')
            if args.stocks:
                (game / 'config' / 'pluginless').mkdir(parents=True, exist_ok=True)
                shutil.copy2(args.stocks, game / 'config' / 'pluginless' / 'stocks.json')
            if args.curios_slots:
                pack = game / 'world/datapacks/carry-test'
                fixture = {'pack.mcmeta': {'pack': {'pack_format': 48, 'description': 'Disposable PDA Curios test slots'}},
                           'data/carry_test/curios/entities/player.json': {'entities': ['minecraft:player'], 'slots': ['charm', 'pda']},
                           'data/carry_test/curios/slots/charm.json': {'size': 1},
                           'data/carry_test/curios/slots/pda.json': {'size': 1},
                           'data/curios/tags/item/pda.json': {'replace': False, 'values': ['wflib:pda']}}
                for name, value in fixture.items():
                    dest = pack / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(json.dumps(value))
            flat = json.dumps({'layers': [{'block': 'minecraft:bedrock', 'height': 1}, {'block': 'minecraft:dirt', 'height': 2}, {'block': 'minecraft:grass_block', 'height': 1}], 'biome': 'minecraft:plains', 'lakes': False, 'features': False})
            (game / 'server.properties').write_text(f'generator-settings={flat}\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port={port}\nlevel-seed=160016\nlevel-type=minecraft:flat\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nmax-tick-time=-1\n')
        else:
            options = 'onboardAccessibility:false\npauseOnLostFocus:false\nskipMultiplayerWarning:true\nrenderDistance:3\nsimulationDistance:5\nguiScale:2\nmaxFps:30\nenableVsync:false\n'
            if not args.show:
                options += 'soundCategory_master:0.0\n'
            (game / 'options.txt').write_text(options)
        command = ['sh', str(ROOT / 'gradlew'), '-p', str(project), 'runHarness', '--console=plain', '--no-daemon',
                   f'-Pmode={mode}', f'-PgameDir={game}', f'-PcontrolDir={control}', f'-Pport={port}',
                   f'-PlongText={str(args.long_text).lower()}']
        if mode == 'client' and args.pda_theme:
            command.append(f'-PpdaTheme={args.pda_theme}')
        command += [f'-P{name}={path}' for name, path in jars.items()]
        return spawn(command, project, mode + '.log')

    def xdo(*values):
        subprocess.run(['xdotool', *map(str, values)], env=env, check=True, capture_output=True)

    # Refuse overlap even if a stale lane-free message races another worker's launch.
    busy = []
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            if (proc / 'exe').resolve().name == 'java' and b'pdaharness' in (proc / 'cmdline').read_bytes():
                busy.append(proc.name)
        except (OSError, ProcessLookupError):
            pass
    with socket.socket() as selftest_probe:
        selftest_busy = selftest_probe.connect_ex(('127.0.0.1', 25598)) == 0
    if busy or selftest_busy:
        raise RuntimeError(f'Validation lane busy (PDA game PIDs={busy}, selftest25598={selftest_busy}); wait for its owner')
    try:
        if not args.show:
            display = next(number for number in range(200, 500) if not Path(f'/tmp/.X11-unix/X{number}').exists() and not Path(f'/tmp/.X{number}-lock').exists())
            env['DISPLAY'] = f':{display}'
            spawn(['Xvfb', env['DISPLAY'], '-screen', '0', '1280x800x24', '-nolisten', 'tcp'], output, 'xvfb.log')
        server = start_game('server')
        seed = wait_for(control / 'server-ready.json', processes)
        client = start_game('client')
        ready = wait_for(control / 'client-ready.json', processes)
        windows = subprocess.check_output(['xdotool', 'search', '--all', '--pid', str(ready['pid']), '--name', 'Minecraft'], env=env, text=True).splitlines()
        window = windows[-1]
        if args.show:
            xdo('windowactivate', '--sync', window)
        xdo('windowfocus', '--sync', window)
        xdo('mousemove', '--window', window, 640, 400)
        xdo('click', 3)
        time.sleep(2)
        steps = [{'op': 'screenshot', 'name': 'pda-home'}, {'op': 'state'}]
        for app in args.apps:
            steps += [{'op': 'app', 'id': app}, {'op': 'wait', 'seconds': 2}, {'op': 'screenshot', 'name': app.replace(':', '-')}]
        steps += steps_from_file
        receipts = []
        carry_token = None
        for number, step in enumerate(steps, 1):
            op = step['op']
            if op == 'key': xdo('key', '--clearmodifiers', step['key'])
            elif op == 'text': xdo('type', '--clearmodifiers', '--delay', 30, '--', step['text'])
            elif op == 'move': xdo('mousemove', '--window', window, step['x'], step['y'])
            elif op == 'click': xdo('click', step.get('button', 1))
            elif op == 'wait': time.sleep(step['seconds'])
            else:
                action = {'op': 'open', 'app': step['id']} if op == 'app' else {key: value for key, value in step.items() if key != 'name'}
                if op.startswith('carry_'):
                    channel = 'carry-server' if op in ('carry_move', 'carry_server_assert', 'carry_kill') else 'carry-client'
                    temporary = control / (channel + '.tmp')
                    temporary.write_text(json.dumps(dict(id=number, **action)))
                    temporary.replace(control / (channel + '.json'))
                else:
                    write_action(control, number, **action)
                receipt = wait_for(control / f'result-{number}.json', processes, 30)
                if not receipt['ok']: raise RuntimeError(receipt)
                if op == 'carry_move':
                    carry_token = receipt['token']
                if op == 'carry_assert' and receipt.get('count') == 1 and receipt.get('token') != carry_token:
                    raise RuntimeError(f'Client/server PDA token mismatch: {receipt}')
                if op == 'state' and not receipt.get('focused'): raise RuntimeError(f'PDA did not focus from mouse use: {receipt}')
                if op == 'screenshot':
                    image = output / 'client/screenshots' / f'capture-{number}.png'
                    if not image.exists() or image.stat().st_size < 1000: raise RuntimeError(f'Screenshot missing/empty: {image}')
                    name = step['name']
                    if not name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in name):
                        raise ValueError('Screenshot name must contain only letters, digits, - or _')
                    shutil.copy2(image, output / (name + '.png'))
                receipts.append(dict(step=step, result=receipt))
        text_errors = [dict(page=r['result'].get('page'), error=e)
                       for r in receipts for e in r['result'].get('text_audit', {}).get('errors', [])]
        (output / 'result.json').write_text(json.dumps({'passed': not text_errors, 'seed': seed,
                                                      'checks': receipts, 'text_errors': text_errors}, indent=2))
        if text_errors:
            raise RuntimeError(f'{len(text_errors)} text audit failures; all pages inspected, see {output / "result.json"}')
        print(f'PASS: focused PDA and {len(args.apps)} apps; screenshots in {output}', flush=True)
        if args.show:
            print('Desktop PDA instance ready. Stop: close the Minecraft window (or press Ctrl+C in the runner terminal); the test server stops too.', flush=True)
            client.wait()
    except KeyboardInterrupt:
        pass
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
        for stream in logs: stream.close()


if __name__ == '__main__':
    main()
