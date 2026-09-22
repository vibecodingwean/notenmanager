"""Offline source wiring regression; palette/runtime tests live in BayernThemeTest."""
import pathlib
import re
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/de/streberalarm/app'

def calls(text):
    for match in re.finditer(r'\b(TextButton|OutlinedButton|FilledTonalButton)\(', text):
        start = match.end()
        depth, quoted, escaped = 1, False, False
        for end in range(start, len(text)):
            char = text[end]
            if quoted:
                if escaped: escaped = False
                elif char == '\\': escaped = True
                elif char == '"': quoted = False
            elif char == '"': quoted = True
            elif char == '(': depth += 1
            elif char == ')':
                depth -= 1
                if depth == 0:
                    yield match.group(1), start, end, text[start:end]
                    break

class BayernButtons(unittest.TestCase):
    def test_delete_labels_are_white_for_dark_action_fills(self):
        text = (SRC / 'Ui.kt').read_text()
        labels = re.findall(r'Text\("Löschen",\s*color\s*=\s*([^\n]+)\)', text)
        self.assertEqual(len(labels), 2)
        for label in labels:
            self.assertEqual(
                label,
                'if (LocalLook.current == AppLook.BAYERN) Color.White else MaterialTheme.colorScheme.error',
            )

    def test_every_secondary_action_uses_bayern_button_colors(self):
        found = 0
        for path in SRC.glob('*.kt'):
            for kind, start, end, args in calls(path.read_text()):
                found += 1
                self.assertIn('bayernButtonColors(', args, f'{path.name}: {kind} at {start}')
        self.assertGreater(found, 50)

    def test_information_cards_keep_white_role(self):
        self.assertIn('primaryContainer = Color.White', (SRC / 'Appearance.kt').read_text())
        self.assertIn('if (bayern) scheme.primaryContainer', (SRC / 'GradeOverviewPanel.kt').read_text())
        self.assertIn('color = scheme.primaryContainer', (SRC / 'VividThemes.kt').read_text())

if __name__ == '__main__': unittest.main()
