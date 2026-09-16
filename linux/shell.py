#!/usr/bin/python3 -I
"""Minimal native IWS shell. No browser chrome or network-management surface."""
import os
from pathlib import Path
import sys
import threading
import math

sys.path.insert(0, str(Path(__file__).resolve().parent))
from shell_policy import PORTAL, allowed_navigation, LoadState
from runtime import wait_for_portal
import gi
gi.require_version('Gtk', '3.0')
gi.require_version('WebKit2', '4.1')
from gi.repository import Gtk, GLib, WebKit2, GdkPixbuf
GLib.set_prgname('iws')
GLib.set_application_name('IWS')

def rail_geometry(width):
    status_x = max(230, width - 96)
    return {'back': (10, 6, 82, 36), 'home': (98, 6, 116, 36),
            'theme': (max(226, status_x - 102), 8, 94, 32),
            'status': (status_x, 8, 86, 32)}

class PaintedFixed(Gtk.Fixed):
    def do_draw(self, cr):
        Gtk.render_background(self.get_style_context(), cr, 0, 0,
                              self.get_allocated_width(), self.get_allocated_height())
        return Gtk.Fixed.do_draw(self, cr)

    def do_get_preferred_width(self):
        return (0, 0)

    def do_get_preferred_height(self):
        return (0, 0)

class ShellRail(PaintedFixed):
    def do_get_preferred_width(self):
        return (900, 1100)

class WindowsGlyph(Gtk.DrawingArea):
    """The same circle/arc geometry used by the Windows native controls."""
    def __init__(self, spinner=False):
        super().__init__()
        self.spinning, self.angle, self.timer, self.dark = spinner, 0, None, False
        self.connect('draw', self.paint)
        self.connect('destroy', lambda *_: self.stop())

    def start(self):
        if self.timer is None:
            self.timer = GLib.timeout_add(80, self.tick)

    def stop(self):
        if self.timer is not None:
            GLib.source_remove(self.timer)
            self.timer = None

    def tick(self):
        self.angle = (self.angle + 24) % 360
        self.queue_draw()
        return True

    def paint(self, _widget, cr):
        def color(value):
            cr.set_source_rgb(*(int(value[i:i+2], 16) / 255 for i in (0, 2, 4)))
        cr.set_line_cap(1)
        if self.spinning:
            cr.set_line_width(2.5)
            color('3d4954' if self.dark else 'dfdfdf')
            cr.arc(11.5, 11.5, 8.5, 0, math.tau)
            cr.stroke()
            color('78b8e0' if self.dark else '1d3686')
            cr.arc(11.5, 11.5, 8.5, math.radians(self.angle), math.radians(self.angle + 95))
        else:
            cr.set_line_width(2)
            color('e4736f' if self.dark else '8b0000')
            cr.arc(19.5, 19.5, 15.5, 0, math.tau)
            cr.stroke()
            cr.move_to(20, 11)
            cr.line_to(20, 23)
            cr.move_to(20, 29)
            cr.line_to(20, 30)
        cr.stroke()
        return False

def shell_css(dark):
    # Same native-shell palette as the accepted Windows client.
    surface, alternate, border, text, muted, accent, background = (
        ('#1e242b', '#232a32', '#3d4954', '#eef1ea', '#9aa6b2', '#78b8e0', '#161a1f')
        if dark else
        ('#ffffff', '#f5f5f5', '#dfdfdf', '#111418', '#5a6470', '#1d3686', '#eef1ea'))
    return f'''
    #iws-rail {{ background: {surface}; color: {text}; }}
    #iws-back, #iws-home, #iws-retry, .iws-theme {{ min-height: 0; min-width: 0; padding: 0;
        border-radius: 0; box-shadow: none; text-shadow: none;
        font-family: "Microsoft Sans Serif", "Liberation Sans", Arial, sans-serif; font-size: 11px; font-weight: 400;
        color: {text}; background: {surface}; border: 1px solid transparent; }}
    #iws-home {{ background: {alternate}; border-color: {border}; }}
    #iws-back:hover, #iws-home:hover {{ background: {alternate}; }}
    #iws-back:disabled {{ color: {muted}; opacity: 1; background: {surface}; }}
    #iws-back:focus, #iws-home:focus {{ outline: 2px solid {accent}; outline-offset: -3px; }}
    #iws-status {{ color: {muted}; font-family: "Segoe UI", "Liberation Sans", sans-serif; font-size: 11.333px; }}
    #iws-theme-group {{ background: {border}; }}
    .iws-theme {{ color: {muted}; background: {alternate}; }}
    .iws-theme:checked {{ color: {text}; background: {surface}; border-color: {border}; }}
    #iws-dot {{ background: {muted}; border-radius: 3px; }}
    #iws-dot.connected {{ background: {'#7fc9a4' if dark else '#2e6b4f'}; }}
    #iws-rule {{ min-height: 2px; border: none; padding: 0; margin: 0;
        background-image: linear-gradient(to right, #5058a0, #6890c8, #78b8e0, #a0c8c8, #b0d0c0); }}
    #iws-message {{ background: {background}; color: {text}; padding: 0; border: none; }}
    #iws-title {{ font-family: "Segoe UI", "Liberation Sans", sans-serif; font-size: 14.667px; font-weight: 700; color: {text}; }}
    #iws-detail {{ font-family: "Segoe UI", "Liberation Sans", sans-serif; font-size: 12.667px; color: {muted}; }}
    #iws-lockup-title {{ font-family: "Segoe UI", "Liberation Sans", sans-serif; font-size: 26.667px; font-weight: 700; color: {text}; }}
    #iws-lockup-company {{ font-family: "Segoe UI", "Liberation Sans", sans-serif; font-size: 9.333px; font-weight: 700; color: {muted}; }}
    #iws-error {{ font-size: 34px; color: {'#e4736f' if dark else '#8b0000'}; }}
    #iws-retry {{ background: {accent}; color: {'#0f1317' if dark else '#ffffff'}; }}
    '''

class IwsWindow(Gtk.Window):
    def __init__(self):
        super().__init__(title='IWS')
        self.set_icon_name('iws')
        self.set_default_size(1100, 760)
        self.connect('destroy', Gtk.main_quit)
        self.state = LoadState()
        self.probing = False
        self.destination = PORTAL
        self.theme_file = Path.home() / 'shell-theme'
        try:
            self.theme_choice = self.theme_file.read_text().strip()
        except OSError:
            self.theme_choice = None
        home = Path.home() / 'webview'
        home.mkdir(mode=0o700, parents=True, exist_ok=True)
        manager = WebKit2.WebsiteDataManager(base_data_directory=str(home / 'data'),
                                           base_cache_directory=str(home / 'cache'))
        manager.set_tls_errors_policy(WebKit2.TLSErrorsPolicy.FAIL)
        self.context = WebKit2.WebContext.new_with_website_data_manager(manager)
        self.context.set_sandbox_enabled(True)
        self.context.connect('download-started', lambda _context, download: download.cancel())
        self.web = WebKit2.WebView.new_with_context(self.context)
        self.web.get_settings().set_enable_developer_extras(False)
        self.web.connect('decide-policy', self.decide_policy)
        self.web.connect('load-changed', self.load_changed)
        self.web.connect('load-failed', self.load_failed)
        self.web.connect('load-failed-with-tls-errors', self.tls_failed)
        self.web.connect('web-process-terminated', lambda *_: self.show_error(False))
        self.web.connect('notify::can-go-back', lambda *_: self.back.set_sensitive(self.web.can_go_back()))

        layout = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        rail = ShellRail()
        rail.set_name('iws-rail')
        rail.set_size_request(-1, 48)
        self.back = Gtk.Button(label='‹  Back')
        self.back.set_name('iws-back')
        self.back.set_size_request(82, 36)
        self.back.set_sensitive(False)
        self.back.connect('clicked', lambda *_: self.web.go_back() if self.web.can_go_back() else None)
        self.portal = Gtk.Button(label='IWS Portal')
        self.portal.set_name('iws-home')
        self.portal.set_size_request(116, 36)
        self.portal.set_image(Gtk.Image.new_from_file('/usr/lib/iws-client/iws-mark-small.png'))
        self.portal.set_always_show_image(True)
        self.portal.connect('clicked', self.go_home)
        self.status = Gtk.Label(label='')
        self.status.set_name('iws-status')
        self.status.set_xalign(0)
        self.status.set_size_request(72, 32)
        status_control = Gtk.Fixed()
        self.dot = Gtk.DrawingArea()
        self.dot.set_name('iws-dot')
        self.dot.set_size_request(6, 6)
        self.dot.connect('draw', lambda widget, cr: Gtk.render_background(
            widget.get_style_context(), cr, 0, 0, 6, 6))
        status_control.put(self.dot, 2, 13)
        status_control.put(self.status, 14, 0)
        theme = PaintedFixed()
        theme.set_name('iws-theme-group')
        self.theme_buttons = {}
        for choice, x in [('light', 2), ('dark', 48)]:
            button = Gtk.ToggleButton(label=choice.title())
            button.get_style_context().add_class('iws-theme')
            button.set_size_request(44, 28)
            button.connect('clicked', lambda _button, value=choice: self.choose_theme(value))
            theme.put(button, x, 2)
            self.theme_buttons[choice] = button
        controls = {'back': self.back, 'home': self.portal, 'theme': theme, 'status': status_control}
        for name, widget in controls.items():
            x, y, width, height = rail_geometry(1100)[name]
            widget.set_size_request(width, height)
            rail.put(widget, x, y)
        def layout_rail(_widget, allocation):
            for name, widget in controls.items():
                x, y, _, _ = rail_geometry(allocation.width)[name]
                rail.move(widget, x, y)
        rail.connect('size-allocate', layout_rail)
        layout.pack_start(rail, False, False, 0)
        rule = Gtk.Separator()
        rule.set_name('iws-rule')
        layout.pack_start(rule, False, False, 0)
        overlay = Gtk.Overlay()
        overlay.add(self.web)
        self.message = PaintedFixed()
        self.message.set_name('iws-message')
        self.message.set_halign(Gtk.Align.FILL)
        self.message.set_valign(Gtk.Align.FILL)
        self.message_label = Gtk.Label(label='Connecting to IWS…')
        self.message_label.set_name('iws-title')
        self.detail = Gtk.Label(label='')
        self.detail.set_name('iws-detail')
        self.lockup = Gtk.Fixed()
        self.lockup.set_size_request(310, 62)
        brand = Gtk.Image.new_from_pixbuf(GdkPixbuf.Pixbuf.new_from_file_at_scale(
            '/usr/lib/iws-client/iws-mark-full.png', 78, 60, True))
        self.lockup.put(brand, 0, 1)
        title = Gtk.Label(label='IWS')
        title.set_name('iws-lockup-title')
        self.lockup.put(title, 92, 5)
        company = Gtk.Label(label='IMPACT WIRING SOLUTIONS')
        company.set_name('iws-lockup-company')
        self.lockup.put(company, 94, 40)
        self.spinner = WindowsGlyph(spinner=True)
        self.spinner.set_size_request(24, 24)
        self.error_glyph = WindowsGlyph()
        self.error_glyph.set_name('iws-error')
        self.error_glyph.set_size_request(40, 40)
        self.retry = Gtk.Button(label='Retry')
        self.retry.set_name('iws-retry')
        self.retry.set_size_request(118, 42)
        self.retry.connect('clicked', lambda *_: self.begin_load(self.destination))
        for widget in (self.lockup, self.spinner, self.error_glyph, self.message_label, self.detail, self.retry):
            self.message.put(widget, 0, 0)
        self.message.connect('size-allocate', lambda *_: GLib.idle_add(self.relayout_message))
        overlay.add_overlay(self.message)
        layout.pack_start(overlay, True, True, 0)
        self.add(layout)
        self.style = Gtk.CssProvider()
        Gtk.StyleContext.add_provider_for_screen(self.get_screen(), self.style,
                                                Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        settings = Gtk.Settings.get_default()
        settings.connect('notify::gtk-theme-name', self.apply_theme)
        settings.connect('notify::gtk-application-prefer-dark-theme', self.apply_theme)
        self.apply_theme()
        self.show_all()
        self.begin_load(PORTAL)

    def apply_theme(self, *_):
        settings = Gtk.Settings.get_default()
        dark = (settings.get_property('gtk-application-prefer-dark-theme') or
                'dark' in (settings.get_property('gtk-theme-name') or '').lower())
        if self.theme_choice in ('light', 'dark'):
            dark = self.theme_choice == 'dark'
        self.style.load_from_data(shell_css(dark).encode())
        for glyph in (self.spinner, self.error_glyph):
            glyph.dark = dark
            glyph.queue_draw()
        self.applying_theme = True
        try:
            for choice, button in self.theme_buttons.items():
                button.set_active((choice == 'dark') == dark)
        finally:
            self.applying_theme = False
        self.update_indicator()

    def choose_theme(self, choice):
        if getattr(self, 'applying_theme', False):
            return
        self.theme_choice = choice
        self.theme_file.write_text(choice)
        self.apply_theme()

    def update_indicator(self):
        connected = self.state.state == 'ready'
        self.status.set_text('Connected' if connected else '')
        context = self.dot.get_style_context()
        (context.add_class if connected else context.remove_class)('connected')

    def layout_message(self, _widget, allocation):
        cx, cy = allocation.width // 2, allocation.height // 2
        error = self.retry.get_visible()
        self.message.move(self.lockup, cx - 155, cy - 92)
        self.message.move(self.spinner, cx - 12, cy - 9)
        self.message.move(self.error_glyph, cx - 20, cy - 92)
        for widget, y in ((self.message_label, cy - 37 if error else cy + 30),
                          (self.detail, cy - 4), (self.retry, cy + 39)):
            self.message.move(widget, cx - widget.get_preferred_width()[1] // 2, y)

    def relayout_message(self):
        self.layout_message(self.message, self.message.get_allocation())
        return False

    def begin_load(self, uri):
        if not allowed_navigation(uri):
            return
        self.destination = uri
        if self.probing:
            return
        self.probing = True
        self.state.started()
        self.update_indicator()
        self.message_label.set_text('Connecting to IWS…')
        self.lockup.show()
        self.spinner.show()
        self.spinner.start()
        self.error_glyph.hide()
        self.detail.hide()
        self.message.show()
        self.retry.hide()
        def probe():
            failure = None
            try:
                wait_for_portal()
            except Exception as error:
                failure = str(error)
            GLib.idle_add(self.probe_finished, failure)
        threading.Thread(target=probe, daemon=True).start()

    def probe_finished(self, failure):
        self.probing = False
        if failure:
            self.show_error(failure == 'IWS_CERTIFICATE_INVALID')
        else:
            self.web.load_uri(self.destination)
        return False

    def go_home(self, *_):
        self.begin_load(PORTAL)

    def decide_policy(self, _view, decision, kind):
        if kind in (WebKit2.PolicyDecisionType.NAVIGATION_ACTION, WebKit2.PolicyDecisionType.NEW_WINDOW_ACTION):
            uri = decision.get_navigation_action().get_request().get_uri()
            if not allowed_navigation(uri):
                decision.ignore()
                return True
            if kind == WebKit2.PolicyDecisionType.NEW_WINDOW_ACTION:
                decision.ignore()
                self.web.load_uri(uri)
                return True
        return False

    def load_changed(self, _view, event):
        if event == WebKit2.LoadEvent.STARTED:
            self.state.started()
            self.update_indicator()
        elif event == WebKit2.LoadEvent.FINISHED:
            self.state.finished()
            if self.state.state == 'ready':
                self.message.hide()
                self.spinner.stop()
                self.update_indicator()
            self.back.set_sensitive(self.web.can_go_back())

    def show_error(self, tls):
        self.state.failed(tls)
        self.update_indicator()
        self.message_label.set_text('IWS is unavailable.')
        self.detail.set_text('IWS could not verify this secure connection.' if tls
                             else 'Check your connection and try again.')
        self.detail.show()
        self.lockup.hide()
        self.spinner.stop()
        self.spinner.hide()
        self.error_glyph.show()
        self.retry.show()
        self.message.show()
        return True

    def load_failed(self, _view, _event, uri, error):
        if error.matches(WebKit2.network_error_quark(), WebKit2.NetworkError.CANCELLED):
            return True
        if allowed_navigation(uri):
            self.destination = uri
        return self.show_error(self.state.state == 'certificate-error')

    def tls_failed(self, *_):
        # Report the rejected connection; never register a certificate exception.
        return self.show_error(True)

def main():
    # The root-private namespace handle stays inaccessible. This is a launcher
    # sanity check; kernel namespace membership, not this environment value,
    # supplies the network boundary.
    if os.geteuid() == 0 or str(os.stat('/proc/self/ns/net').st_ino) != os.environ.get('IWS_NAMESPACE_INODE'):
        raise RuntimeError('Launch IWS using the iws command.')
    IwsWindow().maximize()
    Gtk.main()

if __name__ == '__main__': main()
