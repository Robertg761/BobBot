import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('team_bots', Path(__file__).parents[1] / 'bobbot-team' / 'bots.py')
bots = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bots)


class BotModeMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.profile = Path(self.temp.name)

    def test_missing_profile_yaml_means_off(self):
        self.assertIsNone(bots.bot_mode(self.profile))

    def test_enable_writes_desktop_shape_and_bumps_revision(self):
        (self.profile / 'profile.yaml').write_text('description: Research bot\nui_meta:\n  other: 1\n')
        block = bots.set_bot_mode(self.profile, title='Research and web digging')
        self.assertEqual(block, {'title': 'Research and web digging', 'managed_by': 'bobbot'})
        data = bots._read_yaml(self.profile / 'profile.yaml')
        self.assertEqual(data['description'], 'Research bot')
        self.assertEqual(data['ui_meta']['other'], 1)
        self.assertEqual(data['_ui_meta_revisions']['hermes-bots'], 1)
        bots.set_bot_mode(self.profile, title='Research')
        self.assertEqual(bots._read_yaml(self.profile / 'profile.yaml')['_ui_meta_revisions']['hermes-bots'], 2)

    def test_title_is_single_line_and_bounded(self):
        block = bots.set_bot_mode(self.profile, title='  spans\nlines   and  spaces ' + 'x' * 400)
        self.assertNotIn('\n', block['title'])
        self.assertLessEqual(len(block['title']), 160)

    def test_disable_removes_block_but_keeps_revision(self):
        bots.set_bot_mode(self.profile, title='A')
        bots.set_bot_mode(self.profile, enabled=False)
        self.assertIsNone(bots.bot_mode(self.profile))
        self.assertEqual(bots._read_yaml(self.profile / 'profile.yaml')['_ui_meta_revisions']['hermes-bots'], 2)
        self.assertNotIn('ui_meta', bots._read_yaml(self.profile / 'profile.yaml'))

    def test_existing_desktop_block_keeps_its_fields(self):
        (self.profile / 'profile.yaml').write_text('ui_meta:\n  hermes-bots:\n    title: Old\n    avatar: cat\n_ui_meta_revisions:\n  hermes-bots: 7\n')
        block = bots.set_bot_mode(self.profile, title='New')
        self.assertEqual(block['avatar'], 'cat')
        self.assertEqual(block['title'], 'New')
        self.assertEqual(bots._read_yaml(self.profile / 'profile.yaml')['_ui_meta_revisions']['hermes-bots'], 8)


class PersonaAndNamesTest(unittest.TestCase):
    def test_persona_heading_is_the_bot_name(self):
        text = bots.persona_text('research-bot', 'Digs through the web.', 'Be terse.')
        self.assertTrue(text.startswith('# Research Bot\n'))
        self.assertIn('You are Research Bot.', text)
        self.assertIn('Digs through the web.', text)
        self.assertTrue(text.endswith('Be terse.\n'))

    def test_custom_persona_with_heading_is_kept(self):
        self.assertEqual(bots.persona_text('x', 'r', '# Zed\n\nHi'), '# Zed\n\nHi\n')

    def test_names(self):
        self.assertEqual(bots.validate_name('steve_2'), 'steve_2')
        for bad in ('Steve', 'default', '', 'a b', '-x', 'x' * 65, None):
            with self.assertRaises(ValueError):
                bots.validate_name(bad)


if __name__ == '__main__':
    unittest.main()
