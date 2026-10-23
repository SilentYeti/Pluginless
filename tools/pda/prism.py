#!/usr/bin/env python3
"""Create/refresh only the marked Pluginless PDA Prism instance; never replace a test save."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess

from run import ROOT, cached

DEFAULT_INSTANCE = Path.home() / '.local/share/PrismLauncher/instances/Pluginless-PDA-Test'
MARKER = 'pluginless-pda-instance.json'


def read_tag(stream, kind):
    def unpack(fmt):
        return struct.unpack('>' + fmt, stream.read(struct.calcsize('>' + fmt)))[0]
    if kind in (1, 2, 3, 4, 5, 6):
        return unpack({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind])
    if kind == 7:
        return stream.read(unpack('i'))
    if kind == 8:
        return stream.read(unpack('H')).decode('utf-8')
    if kind == 9:
        child, count = unpack('B'), unpack('i')
        return child, [read_tag(stream, child) for _ in range(count)]
    if kind == 10:
        result = {}
        while True:
            child = unpack('B')
            if child == 0:
                return result
            name = read_tag(stream, 8)
            result[name] = child, read_tag(stream, child)
    if kind in (11, 12):
        return [unpack('i' if kind == 11 else 'q') for _ in range(unpack('i'))]
    raise ValueError(f'Unknown NBT tag {kind}')


def write_tag(kind, value):
    def pack(fmt, data):
        return struct.pack('>' + fmt, data)
    if kind in (1, 2, 3, 4, 5, 6):
        return pack({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind], value)
    if kind == 7:
        return pack('i', len(value)) + value
    if kind == 8:
        data = value.encode('utf-8')
        return pack('H', len(data)) + data
    if kind == 9:
        child, items = value
        return pack('B', child) + pack('i', len(items)) + b''.join(write_tag(child, v) for v in items)
    if kind == 10:
        return b''.join(pack('B', child) + write_tag(8, name) + write_tag(child, item)
                        for name, (child, item) in value.items()) + b'\0'
    if kind in (11, 12):
        return pack('i', len(value)) + b''.join(pack('i' if kind == 11 else 'q', v) for v in value)
    raise ValueError(f'Unknown NBT tag {kind}')


def read_nbt(path):
    stream = io.BytesIO(gzip.decompress(path.read_bytes()))
    if stream.read(1) != b'\x0a':
        raise ValueError(f'{path} is not a compound NBT save')
    name = read_tag(stream, 8)
    return name, read_tag(stream, 10)


def seed_world(source, destination):
    if destination.exists():
        raise FileExistsError(f'Keep your existing test world: {destination}; omit --seed-world when refreshing')
    name, level = read_nbt(source / 'level.dat')
    data = level['Data'][1]
    data.update(allowCommands=(1, 1), GameType=(3, 1), LevelName=(8, 'Pluginless PDA test'))
    players = list((source / 'playerdata').glob('*.dat'))
    if len(players) == 1:
        _, player = read_nbt(players[0])
        player.pop('UUID', None)  # keep the real singleplayer profile's identity
        data['Player'] = 10, player
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns('session.lock'))
    (destination / 'level.dat').write_bytes(gzip.compress(b'\x0a' + write_tag(8, name) + write_tag(10, level)))


def provenance(path):
    entry = {'source': str(path), 'sha256_at_refresh': hashlib.sha256(path.read_bytes()).hexdigest()}
    result = subprocess.run(['git', '-C', str(path.parent), 'rev-parse', 'HEAD'], capture_output=True, text=True)
    if result.returncode == 0:
        entry['checkout_head_at_refresh'] = result.stdout.strip()
    return entry


def setup(instance, pluginless, wflib, world=None):
    marker = instance / MARKER
    if instance.is_symlink() or (instance.exists() and not marker.is_file()):
        raise FileExistsError(f'Refusing an existing/unmarked instance: {instance}')
    previous = json.loads(marker.read_text()) if marker.exists() else {}
    if previous and previous.get('format') != 'pluginless-pda-v1':
        raise ValueError('Instance marker is not owned by this helper')
    jars = [pluginless, wflib,
            cached('com.norwood', 'ahf-1.21.1-neoforge', '1.0.8-SNAPSHOT'),
            cached('com.lowdragmc.ldlib2', 'ldlib2-neoforge-1.21.1', '2.2.26'),
            cached('com.wf.gemrender', 'gemrender-1.21.1', '0.1.17-SNAPSHOT'),
            cached('dev.engine-room.flywheel', 'flywheel-neoforge-1.21.1', '1.0.6')]
    jars = [p.resolve(strict=True) for p in jars]
    if len({p.name for p in jars}) != len(jars):
        raise ValueError('Mod jar filenames must be unique')
    fresh = not instance.exists()
    game = instance / 'minecraft'
    mods = game / 'mods'
    world_target = game / 'saves/Pluginless-PDA-Test'
    if world and world_target.exists():
        raise FileExistsError('Never overwrite the existing test world; omit --seed-world')
    # Validate collisions before changing an already managed instance.
    for jar in jars:
        dest = mods / jar.name
        if dest.exists() or dest.is_symlink():
            if jar.name not in previous.get('mods', {}) or not dest.is_symlink():
                raise FileExistsError(f'Refusing an unmanaged mod: {dest}')
    mods.mkdir(parents=True, exist_ok=True)
    if fresh:
        (instance / 'instance.cfg').write_text('[General]\nInstanceType=OneSix\nname=Pluginless PDA test\n'
            'OverrideJavaLocation=true\nJavaPath=/usr/lib/jvm/java-21-openjdk/bin/java\n'
            'OverrideMemory=true\nMinMemAlloc=512\nMaxMemAlloc=4096\n'
            'OverrideWindow=true\nMinecraftWinWidth=1280\nMinecraftWinHeight=800\n')
        (instance / 'mmc-pack.json').write_text(json.dumps({'formatVersion': 1, 'components': [
            {'uid': 'org.lwjgl3', 'version': '3.3.3', 'dependencyOnly': True, 'cachedName': 'LWJGL 3', 'cachedVersion': '3.3.3'},
            {'uid': 'net.minecraft', 'version': '1.21.1', 'important': True, 'cachedName': 'Minecraft', 'cachedVersion': '1.21.1'},
            {'uid': 'net.neoforged', 'version': '21.1.248', 'cachedName': 'NeoForge', 'cachedVersion': '21.1.248',
             'cachedRequires': [{'uid': 'net.minecraft', 'equals': '1.21.1'}]}
        ]}, indent=2))
        (game / 'options.txt').write_text('onboardAccessibility:false\nskipMultiplayerWarning:true\n'
            'renderDistance:8\nsimulationDistance:5\nguiScale:2\nsoundCategory_master:1.0\n')
    for old in previous.get('mods', {}):
        if Path(old).name != old:
            raise ValueError('Invalid managed mod name')
        if old not in {p.name for p in jars} and (mods / old).is_symlink():
            (mods / old).unlink()
    manifest = {}
    for jar in jars:
        dest = mods / jar.name
        if dest.is_symlink():
            dest.unlink()
        dest.symlink_to(jar)
        manifest[jar.name] = provenance(jar)
    if world:
        seed_world(world.resolve(strict=True), world_target)
    receipt = {'format': 'pluginless-pda-v1', 'name': 'Pluginless PDA test', 'instance': str(instance),
               'mods': manifest, 'world': str(world_target) if world_target.exists() else None}
    marker.write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))
    print(f'Launch: prismlauncher --launch {instance.name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', type=Path, default=DEFAULT_INSTANCE)
    parser.add_argument('--pluginless-jar', type=Path, help='Combined PDA build output; refresh defaults to the existing managed link')
    parser.add_argument('--wflib-jar', type=Path, help='WFLib build output; defaults to the previous link or harness version')
    parser.add_argument('--seed-world', type=Path, help='Copy a stopped disposable harness world; never overwrite a save')
    args = parser.parse_args()
    marker = args.instance / MARKER
    previous = json.loads(marker.read_text()).get('mods', {}) if marker.is_file() else {}
    def remembered(prefix):
        sources = [Path(v['source']) for k, v in previous.items() if k.startswith(prefix)]
        return sources[0] if len(sources) == 1 else None
    pluginless = args.pluginless_jar or remembered('pluginless-')
    if not pluginless:
        parser.error('first setup requires --pluginless-jar pointing to the combined PDA build')
    wflib = args.wflib_jar or remembered('wflib-') or ROOT.parent / 'WFLib-main/build/libs/wflib-1.8.36.jar'
    setup(args.instance.absolute(), pluginless, wflib, args.seed_world)


if __name__ == '__main__':
    main()
