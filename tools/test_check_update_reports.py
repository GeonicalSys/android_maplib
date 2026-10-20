import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import yaml

spec = importlib.util.spec_from_file_location('report_guard', Path(__file__).with_name('check_update_reports.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ReportGuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='update-report-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Report guard test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.write('tools/update-report-policy.json', json.dumps(dict(
            reports_dir='docs/update-reports', id_prefix='lisa-', base_branch='main')))
        self.write('plugins/a/main.py', 'old\n')
        self.card('old', covers=['plugins/a/main.py'])
        self.commit()
        self.git('update-ref', 'refs/remotes/origin/main', self.git('rev-parse', 'HEAD').strip())

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.STDOUT).decode('utf-8')

    def write(self, path, text):
        p = self.root/path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding='utf-8')

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')

    def card(self, slug='new', **extra):
        id = 'lisa-20261020-' + slug
        data = dict(id=id, area='Карта и слои', kind='user', at='2026-10-20T12:00:00+02:00',
                    title='Исправлена карта', why='Пользователь сохраняет правильный результат')
        data.update(extra)
        self.write(f'docs/update-reports/entries/{id}.md', '---\n' + yaml.safe_dump(data, allow_unicode=True) + '---\n\nИзменение.\n')
        return f'docs/update-reports/entries/{id}.md'

    def check(self, **kw):
        return guard.check(self.root, **kw)

    def test_missing_card_blocks(self):
        self.write('plugins/a/main.py', 'new\n')
        self.assertEqual(['plugins/a/main.py'], self.check()['uncovered'])

    def test_new_card_covers_exact_source(self):
        self.write('plugins/a/main.py', 'new\n')
        self.card(covers=['plugins/a/main.py'])
        self.assertTrue(self.check()['ok'])

    def test_unrelated_card_does_not_cover_other_component(self):
        self.write('plugins/a/main.py', 'new\n')
        self.write('plugins/b/types.py', 'forest types\n')
        self.card(covers=['plugins/a/main.py'])
        self.assertEqual(['plugins/b/types.py'], self.check()['uncovered'])

    def test_same_component_omission_blocks(self):
        self.write('plugins/a/main.py', 'new\n')
        self.write('plugins/a/other.py', 'other fix\n')
        self.card(covers=['plugins/a/main.py'])
        self.assertEqual(['plugins/a/other.py'], self.check()['uncovered'])

    def test_existing_card_cannot_hide_new_work(self):
        self.write('plugins/a/main.py', 'new\n')
        self.card('old', covers=['plugins/a/main.py'], title='Дописана новая функция')
        self.assertFalse(self.check()['ok'])

    def test_bad_kind_is_rejected_even_without_diff(self):
        self.card('old', kind='fix')
        self.assertIn('kind must be user or internal', '\n'.join(self.check(schema_only=True)['errors']))

    def test_unquoted_colon_is_rejected(self):
        path = self.card('old')
        raw = (self.root/path).read_text(encoding='utf-8').replace('why: ', 'why: Причина: ')
        self.write(path, raw)
        self.assertFalse(self.check(schema_only=True)['ok'])

    def test_duplicate_yaml_key_is_rejected(self):
        path = self.card('old')
        raw = (self.root/path).read_text(encoding='utf-8').replace('kind: user', 'kind: user\nkind: internal')
        self.write(path, raw)
        self.assertIn('duplicate YAML key', '\n'.join(self.check(schema_only=True)['errors']))

    def test_missing_coverage_on_new_card_is_rejected(self):
        self.card()
        self.assertFalse(self.check()['ok'])

    def test_catchall_glob_is_rejected(self):
        self.card(covers=['plugins/**'])
        self.assertFalse(self.check()['ok'])

    def test_naive_datetime_is_rejected(self):
        self.card('old', at='2026-10-20T12:00:00')
        self.assertFalse(self.check(schema_only=True)['ok'])

    def test_wrong_prefix_is_rejected(self):
        self.card('old', id='android-20261020-old')
        self.assertFalse(self.check(schema_only=True)['ok'])

    def test_deleted_source_requires_card(self):
        (self.root/'plugins/a/main.py').unlink()
        self.assertEqual(['plugins/a/main.py'], self.check()['uncovered'])

    def test_card_deletion_is_rejected(self):
        (self.root/'docs/update-reports/entries/lisa-20261020-old.md').unlink()
        self.assertIn('must not be deleted', '\n'.join(self.check()['errors']))

    def test_rename_requires_both_paths(self):
        (self.root/'plugins/a/main.py').rename(self.root/'plugins/a/renamed.py')
        self.card(covers=['plugins/a/main.py'])
        self.assertEqual(['plugins/a/renamed.py'], self.check()['uncovered'])

    def exemption(self, **kw):
        data = dict(category='no-behavior', reason='Только форматирование без изменения поведения.', paths=['plugins/a/main.py'])
        data.update(kw)
        self.write('docs/update-reports/exemptions/format.yaml', yaml.safe_dump(data, allow_unicode=True))

    def test_explicit_reasoned_exemption(self):
        self.write('plugins/a/main.py', 'format\n')
        self.exemption()
        self.assertTrue(self.check()['ok'])

    def test_old_exemption_cannot_be_reused(self):
        self.exemption()
        self.commit()
        self.git('update-ref', 'refs/remotes/origin/main', self.git('rev-parse', 'HEAD').strip())
        self.write('plugins/a/main.py', 'new\n')
        self.assertFalse(self.check()['ok'])

    def test_vague_exemption_is_rejected(self):
        self.write('plugins/a/main.py', 'new\n')
        self.exemption(reason='skip')
        self.assertFalse(self.check()['ok'])

    def test_accepted_card_is_immutable(self):
        self.write('docs/update-reports/reported/20261020T1200.yaml', 'entry_ids:\n- lisa-20261020-old\n')
        self.commit()
        self.git('update-ref', 'refs/remotes/origin/main', self.git('rev-parse', 'HEAD').strip())
        self.card('old', title='Изменён закрытый пункт')
        self.assertIn('accepted card is immutable', '\n'.join(self.check()['errors']))

    def test_push_checks_committed_tree_not_uncommitted_card(self):
        self.write('plugins/a/main.py', 'new\n')
        self.commit()
        head = self.git('rev-parse', 'HEAD').strip()
        self.card(covers=['plugins/a/main.py'])
        self.assertTrue(self.check()['ok'])
        self.assertFalse(self.check(head=head)['ok'])

    def test_docs_only_edit_needs_no_card(self):
        self.write('docs/guide.md', 'Исправлена формулировка.\n')
        self.assertTrue(self.check()['ok'])

    def test_test_code_needs_card(self):
        self.write('tools/test_business.py', 'assert True\n')
        self.assertEqual(['tools/test_business.py'], self.check()['uncovered'])

    def test_missing_base_fails_closed(self):
        with self.assertRaises(ValueError):
            self.check(base='refs/remotes/origin/missing')

    def submodule(self):
        temp = tempfile.TemporaryDirectory(prefix='report-child-')
        self.addCleanup(temp.cleanup)
        child = Path(temp.name)
        def command(*args):
            subprocess.check_output(['git', '-C', str(child), *args], stderr=subprocess.STDOUT)
        command('init', '-q')
        command('config', 'user.name', 'Report test')
        command('config', 'user.email', 'test@example.invalid')
        command('config', 'commit.gpgsign', 'false')
        (child/'source.py').write_text('old\n', encoding='utf-8')
        command('add', '.')
        command('commit', '-qm', 'child')
        self.git('-c', 'protocol.file.allow=always', 'submodule', 'add', str(child), 'nested')
        self.commit()
        self.git('update-ref', 'refs/remotes/origin/main', self.git('rev-parse', 'HEAD').strip())
        return self.root/'nested'

    def test_dirty_submodule_is_not_parent_pin_change(self):
        child = self.submodule()
        (child/'source.py').write_text('changed in another owner\n', encoding='utf-8')
        self.assertTrue(self.check()['ok'])

    def test_staged_submodule_pin_requires_card(self):
        child = self.submodule()
        (child/'source.py').write_text('new\n', encoding='utf-8')
        subprocess.check_output(['git', '-C', str(child), 'add', '.'])
        subprocess.check_output(['git', '-C', str(child), '-c', 'commit.gpgsign=false',
                                 '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                                 'commit', '-qm', 'next'])
        self.git('add', 'nested')
        self.assertEqual(['nested'], self.check()['uncovered'])

    def test_hook_install_does_not_overwrite_existing_setup(self):
        self.git('config', '--local', 'core.hooksPath', 'existing-hooks')
        self.write('.githooks/pre-push', '#!/bin/sh\nexit 0\n')
        with self.assertRaises(ValueError):
            guard.install_hook(self.root)
        self.assertEqual('existing-hooks', self.git('config', '--get', 'core.hooksPath').strip())


if __name__ == '__main__':
    unittest.main()
