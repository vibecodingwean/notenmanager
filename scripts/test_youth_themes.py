"""Offline integration guards for the three static youth designs."""
import pathlib
import hashlib
import xml.etree.ElementTree as ET
import unittest
R = pathlib.Path(__file__).resolve().parents[1]
S = R / 'app/src/main/java/de/streberalarm/app'
class YouthThemes(unittest.TestCase):
    def test_existing_seven_palette_definitions_are_exact(self):
        s = (S / 'Appearance.kt').read_text().split('fun lookScheme(look: AppLook): ColorScheme =')[1].split('fun categoryTint')[0]
        s = s.replace('        AppLook.STREET_ART, AppLook.BUBBLEGUM, AppLook.SPEED -> youthScheme(look)\n', '')
        self.assertEqual('aa0e369d56731169f73ab96b1a0f7e77c5485f9eb67e8035696a039c783b9272', hashlib.sha256(s.encode()).hexdigest())
    def test_native_dialog_contrast_and_static_spinner(self):
        root = ET.parse(R / 'app/src/main/res/values/appearance.xml').getroot()
        styles = {s.attrib['name']: {i.attrib['name']: i.text for i in s} for s in root}
        def luminance(value):
            rgb = [int(value[i:i+2], 16)/255 for i in (1,3,5)]
            rgb = [x/12.92 if x <= .04045 else ((x+.055)/1.055)**2.4 for x in rgb]
            return sum(x*w for x,w in zip(rgb, (.2126,.7152,.0722)))
        for name in ('StreetArt', 'Bubblegum', 'Speed'):
            d = styles[name + 'DateDialog']
            bg = luminance(d['android:colorBackground'])
            for role in ('textColorPrimary', 'textColorSecondary', 'textColorTertiary', 'colorAccent'):
                fg = luminance(d['android:' + role])
                self.assertGreaterEqual((max(fg,bg)+.05)/(min(fg,bg)+.05), 4.5)
            self.assertEqual('1.0', d['android:disabledAlpha'])
            self.assertEqual('spinner', styles[name + 'DateWidget']['android:datePickerMode'])
    def test_stable_ids(self):
        s = (S / 'Appearance.kt').read_text()
        for key in ('streetart', 'bubblegum', 'speed'):
            self.assertIn('("' + key + '",', s)
    def test_static_early_return(self):
        s = (S / 'Appearance.kt').read_text().split('private fun LookBackdrop')[1]
        self.assertLess(s.index('if (appearance.look.isYouth)'), s.index('lookMotionAllowed('))
        self.assertIn('return', s[:s.index('lookMotionAllowed(')])
    def test_static_decoration_has_no_animation_api(self):
        s = (S / 'YouthThemes.kt').read_text()
        for forbidden in ('rememberInfiniteTransition', 'LaunchedEffect', 'animateFloat', 'infiniteRepeatable', 'delay('):
            self.assertNotIn(forbidden, s)
        ui = (S / 'Ui.kt').read_text()
        self.assertIn('YouthPageHeading(title, subtitle)', ui)
        self.assertIn('if (LocalLook.current.isYouth) YouthRibbon(LocalLook.current)', ui)
        self.assertIn('actionButtonColors(look, ButtonDefaults.buttonColors(', ui)
    def test_decorations_and_dialogs(self):
        self.assertTrue((S / 'YouthThemes.kt').exists())
        for name in ('StreetArt', 'Bubblegum', 'Speed'):
            xml = (R / 'app/src/main/res/values/appearance.xml').read_text()
            for kind in ('Date', 'Clock'):
                self.assertIn(name + kind + 'Dialog', xml)
if __name__ == '__main__': unittest.main()
