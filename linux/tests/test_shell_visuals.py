"""Presentation regression: both approved palettes must parse as real GTK CSS."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import shell


class ShellVisuals(unittest.TestCase):
    def test_light_and_dark_styles_are_valid_and_keep_back_readable(self):
        for dark in (False, True):
            with self.subTest(dark=dark):
                provider = shell.Gtk.CssProvider()
                errors = []
                provider.connect('parsing-error', lambda _p, _s, error: errors.append(error))
                css = shell.shell_css(dark)
                provider.load_from_data(css.encode())
                self.assertEqual(errors, [])
                # An opaque, explicit disabled foreground prevents host themes
                # making the persistent Back control nearly invisible.
                self.assertIn('#iws-back:disabled', css)
                self.assertIn('opacity: 1', css)
                self.assertIn('#9aa6b2' if dark else '#5a6470', css)


if __name__ == '__main__':
    unittest.main()
