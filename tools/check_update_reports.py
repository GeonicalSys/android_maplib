#!/usr/bin/env python3
"""Validate report cards and require new IDs covering every changed source path."""
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

try:
    import yaml
except ImportError:
    raise SystemExit('Install tools/requirements-update-reports.txt in this Python environment.')


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f'duplicate YAML key: {key}')
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def git(root, *args):
    proc = subprocess.run(['git', '-C', str(root), *args], capture_output=True)
    if proc.returncode:
        # Do not print remote URLs, environment variables or credential helpers.
        raise ValueError('Git input unavailable: ' + ' '.join(args[:2]))
    return proc.stdout.decode('utf-8')


def load_yaml(raw):
    value = yaml.load(raw, Loader=UniqueLoader)
    if not isinstance(value, dict):
        raise ValueError('expected a YAML mapping')
    return value


def source_path(value):
    return (isinstance(value, str) and bool(value) and not any(c in value for c in '\\*?[]\n\r\0:')
            and not value.startswith('/') and str(PurePosixPath(value)) == value
            and all(p not in ('.', '..') for p in value.split('/')))


def path_list(value):
    if not isinstance(value, list) or not value or not all(source_path(x) for x in value):
        raise ValueError('covers/paths must be a nonempty list of exact repository-relative paths; no globs')
    if len(set(value)) != len(value):
        raise ValueError('duplicate coverage path')
    return value


def parse_card(raw, path, prefix, require_covers=False):
    match = re.match(r'\A---\r?\n(.*?)\r?\n---(?:\r?\n|$)', raw, re.S)
    if not match:
        raise ValueError('missing YAML frontmatter delimiters')
    card = load_yaml(match[1])
    required = {'id', 'area', 'kind', 'at', 'title', 'why'}
    missing = required - card.keys()
    if missing:
        raise ValueError('missing fields: ' + ', '.join(sorted(missing)))
    for key in required - {'at'}:
        if not isinstance(card[key], str) or not card[key].strip():
            raise ValueError(f'{key} must be a nonempty string')
    if not re.fullmatch(re.escape(prefix) + r'\d{8}-[a-z0-9]+(?:-[a-z0-9]+)*', card['id']):
        raise ValueError('invalid repository ID prefix or card ID')
    if PurePosixPath(path).stem != card['id']:
        raise ValueError('filename must match id')
    if card['kind'] not in ('user', 'internal'):
        raise ValueError('kind must be user or internal')
    stamp = card['at']
    if isinstance(stamp, str):
        stamp = dt.datetime.fromisoformat(stamp.replace('Z', '+00:00'))
    if not isinstance(stamp, dt.datetime) or stamp.utcoffset() is None:
        raise ValueError('at must be an ISO datetime with timezone')
    if 'covers' in card:
        path_list(card['covers'])
    elif require_covers:
        raise ValueError('new card requires covers with exact paths for this change')
    if 'source_commits' in card:
        commits = card['source_commits']
        if not isinstance(commits, list) or not commits or not all(
                isinstance(c, str) and re.fullmatch('[0-9a-f]{40}', c) for c in commits):
            raise ValueError('source_commits must contain full Git commit hashes')
    return card


def documentation(path):
    p = PurePosixPath(path)
    return (p.name in ('AGENTS.md', '.gitkeep') or p.suffix.lower() == '.md'
            or 'docs' in p.parts and p.suffix.lower() in ('.yaml', '.yml', '.json'))


def check(root, base=None, head=None, schema_only=False):
    root = Path(root).resolve()
    policy = json.loads((root / 'tools/update-report-policy.json').read_text(encoding='utf-8-sig'))
    prefix = policy['id_prefix']
    reports = policy['reports_dir']
    if reports not in ('docs/update-reports', 'update-reports'):
        raise ValueError('invalid reports_dir in policy')
    entries = reports + '/entries/'
    exemptions = reports + '/exemptions/'
    if head:
        head = git(root, 'rev-parse', '--verify', head + '^{commit}').strip()
        names = git(root, 'ls-tree', '-r', '--name-only', '-z', head).split('\0')
        read = lambda path: git(root, 'show', f'{head}:{path}')
    else:
        names = git(root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z').split('\0')
        names = [p for p in names if p and (root / p).is_file()]
        read = lambda path: (root / path).read_text(encoding='utf-8-sig')
    names = sorted(set(filter(None, names)))
    errors = []
    changed = set()
    base_names = set()
    if not schema_only:
        comparison = base or 'origin/' + policy['base_branch']
        base = git(root, 'merge-base', comparison, head or 'HEAD').strip()
        base_names = set(git(root, 'ls-tree', '-r', '--name-only', '-z', base).split('\0'))
        args = ['diff', '--name-only', '--no-renames', '-z', base]
        if head:
            args.append(head)
        else:
            # A sibling task's dirty submodule checkout is not a change to the
            # parent's recorded pin. Staged/committed pins are checked below.
            args.append('--ignore-submodules=all')
        args.append('--')
        changed = set(filter(None, git(root, *args).split('\0')))
        if not head:
            changed.update(filter(None, git(root, 'diff', '--cached', '--name-only',
                                            '--no-renames', '-z', base, '--').split('\0')))
            changed.update(filter(None, git(root, 'ls-files', '--others', '--exclude-standard', '-z').split('\0')))
    cards = {}
    covered = set()
    for path in sorted(base_names - set(names)):
        if path.startswith(entries) and path.endswith('.md') and PurePosixPath(path).name != 'README.md':
            errors.append(f'{path}: existing report cards must not be deleted')
    for path in names:
        if not path.startswith(entries) or not path.endswith('.md') or PurePosixPath(path).name == 'README.md':
            continue
        try:
            if '/' in path[len(entries):]:
                raise ValueError('cards must be directly inside entries/')
            new = not schema_only and path not in base_names
            card = parse_card(read(path), path, prefix, require_covers=new)
            if card['id'] in cards:
                raise ValueError('duplicate card ID')
            cards[card['id']] = path
            if new:
                covered.update(card['covers'])
                for sha in card.get('source_commits', []):
                    git(root, 'cat-file', '-e', sha + '^{commit}')
        except (ValueError, yaml.YAMLError) as exc:
            errors.append(f'{path}: {exc}')
    closed = set()
    for path in names:
        if path.startswith(reports + '/reported/') and path.endswith('.yaml'):
            try:
                snapshot = load_yaml(read(path))
                ids = snapshot.get('entry_ids')
                if not isinstance(ids, list) or not all(isinstance(x, str) for x in ids) or len(set(ids)) != len(ids):
                    raise ValueError('invalid entry_ids in accepted snapshot')
                closed.update(ids)
            except (ValueError, yaml.YAMLError) as exc:
                errors.append(f'{path}: {exc}')
    for id in closed:
        path = entries + id + '.md'
        if path in changed and path in base_names:
            errors.append(f'{path}: an accepted card is immutable; use a new ID')
    for path in sorted(changed):
        if not path.startswith(exemptions) or not path.endswith('.yaml') or path not in names:
            continue
        try:
            if path in base_names:
                raise ValueError('use a new exemption file for a new change')
            item = load_yaml(read(path))
            if set(item) != {'category', 'reason', 'paths'}:
                raise ValueError('exemption requires exactly category, reason, paths')
            if item['category'] not in ('version', 'collect', 'documentation', 'no-behavior'):
                raise ValueError('invalid exemption category')
            if not isinstance(item['reason'], str) or len(item['reason'].strip()) < 20:
                raise ValueError('exemption needs a specific explanation of at least 20 characters')
            paths = path_list(item['paths'])
            if any(p not in changed for p in paths):
                raise ValueError('exemption names files outside this change')
            covered.update(paths)
        except (ValueError, yaml.YAMLError) as exc:
            errors.append(f'{path}: {exc}')
    # Deleted source files also need coverage. Report text and documentation are
    # not product behavior; scripts, tests, hooks and workflows are checked.
    required = {p for p in changed if not p.startswith(reports + '/') and not documentation(p)}
    uncovered = sorted(required - covered)
    errors.extend(f'{path}: no NEW card covers this path (or add an explicit justified exemption)' for path in uncovered)
    return dict(ok=not errors, cards=len(cards), changed=len(changed), covered=sorted(required & covered),
                uncovered=uncovered, errors=errors, base=base, head=head or 'working-tree')


def install_hook(root):
    current = git(root, 'config', '--get-regexp', '^core\\.hooksPath$') if subprocess.run(
        ['git', '-C', str(root), 'config', '--get', 'core.hooksPath'], capture_output=True).returncode == 0 else ''
    if current and current.strip().split(' ', 1)[-1] != '.githooks':
        raise ValueError('Existing core.hooksPath must be reconciled manually; it was not overwritten')
    legacy = Path(git(root, 'rev-parse', '--path-format=absolute', '--git-path', 'hooks/pre-push').strip())
    expected = (Path(root) / '.githooks/pre-push').resolve()
    if legacy.exists() and legacy.resolve() != expected:
        raise ValueError('Existing pre-push hook must be reconciled manually; it was not overwritten')
    if not expected.is_file():
        raise ValueError('Versioned .githooks/pre-push is missing')
    git(root, 'config', '--local', 'core.hooksPath', '.githooks')
    print('Installed repository-local update-report pre-push hook.')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--base')
    parser.add_argument('--head')
    parser.add_argument('--schema-only', action='store_true')
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--install-hook', action='store_true')
    parser.add_argument('--pre-push', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.install_hook:
            install_hook(args.repo)
            return 0
        if args.pre_push:
            for line in sys.stdin:
                fields = line.split()
                if len(fields) != 4 or not re.fullmatch('[0-9a-f]{40,64}', fields[1]):
                    raise ValueError('Invalid pre-push input')
                if set(fields[1]) == {'0'}:
                    continue
                result = check(args.repo, head=fields[1])
                if not result['ok']:
                    print('\n'.join(result['errors']), file=sys.stderr)
                    return 1
            return 0
        result = check(args.repo, args.base, args.head, args.schema_only)
        if args.json:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        elif result['ok']:
            print(f'Update reports PASS: {result["cards"]} cards; {len(result["covered"])} source paths covered.')
        else:
            print('\n'.join(result['errors']), file=sys.stderr)
        return 0 if result['ok'] else 1
    except (OSError, ValueError, KeyError, yaml.YAMLError) as exc:
        print(f'Update reports FAIL: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
