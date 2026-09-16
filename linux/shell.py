#!/usr/bin/python3 -I
"""Minimal native IWS shell. No browser chrome or network-management surface."""
import os
from pathlib import Path
import sys
import threading

sys.path.insert(0, str(Path(__file__).resolve().parent))
from shell_policy import PORTAL, allowed_navigation, LoadState
from runtime import wait_for_portal
import gi
gi.require_version('Gtk', '3.0')
gi.require_version('WebKit2', '4.1')
from gi.repository import Gtk, GLib, WebKit2
GLib.set_prgname('iws')
GLib.set_application_name('IWS')

def shell_css(dark):
    # Same native-shell palette as the accepted Windows client.
    surface, alternate, border, text, muted, accent, background = (
        ('#1e242b', '#232a32', '#3d4954', '#eef1ea', '#9aa6b2', '#78b8e0', '#161a1f')
        if dark else
        ('#ffffff', '#f5f5f5', '#dfdfdf', '#111418', '#5a6470', '#1d3686', '#eef1ea'))
    return f'''
    #iws-rail {{ background: {surface}; color: {text}; font-family: "Open Sans", sans-serif; font-size: 13px; }}
    #iws-back, #iws-home, #iws-retry {{ min-height: 34px; padding: 0 12px;
        border-radius: 4px; box-shadow: none; text-shadow: none;
        font-family: "Open Sans", sans-serif; font-size: 13px; font-weight: 600;
        color: {text}; background: {surface}; border: 1px solid transparent; }}
    #iws-home {{ background: {alternate}; border-color: {border}; }}
    #iws-back:hover, #iws-home:hover {{ background: {alternate}; border-color: {accent}; }}
    #iws-back:disabled {{ color: {muted}; opacity: 1; background: {surface}; }}
    #iws-back:focus, #iws-home:focus {{ outline: 2px solid {accent}; outline-offset: -3px; }}
    #iws-status {{ color: {muted}; font-size: 12px; padding: 0 12px; }}
    #iws-rule {{ min-height: 2px; border: none; padding: 0; margin: 0;
        background-image: linear-gradient(to right, #5058a0, #6890c8, #78b8e0, #b0d0c0); }}
    #iws-message {{ background: {background}; color: {text}; padding: 28px;
        border: 1px solid {border}; border-radius: 8px;
        font-family: "Open Sans", sans-serif; font-size: 14px; }}
    #iws-retry {{ background: #1d3686; color: white; padding: 0 24px; }}
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
        rail = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        rail.set_name('iws-rail')
        rail.set_border_width(6)
        self.back = Gtk.Button(label='‹  Back')
        self.back.set_name('iws-back')
        self.back.set_size_request(82, 36)
        self.back.set_sensitive(False)
        self.back.connect('clicked', lambda *_: self.web.go_back() if self.web.can_go_back() else None)
        self.portal = Gtk.Button(label='IWS Portal')
        self.portal.set_name('iws-home')
        self.portal.set_size_request(116, 36)
        self.portal.set_image(Gtk.Image.new_from_icon_name('iws', Gtk.IconSize.BUTTON))
        self.portal.set_always_show_image(True)
        self.portal.connect('clicked', self.go_home)
        self.status = Gtk.Label(label='Connecting to IWS…')
        self.status.set_name('iws-status')
        rail.pack_start(self.back, False, False, 0)
        rail.pack_start(self.portal, False, False, 0)
        rail.pack_end(self.status, False, False, 0)
        layout.pack_start(rail, False, False, 0)
        rule = Gtk.Separator()
        rule.set_name('iws-rule')
        layout.pack_start(rule, False, False, 0)
        overlay = Gtk.Overlay()
        overlay.add(self.web)
        self.message = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.message.set_name('iws-message')
        self.message.set_halign(Gtk.Align.CENTER)
        self.message.set_valign(Gtk.Align.CENTER)
        self.message.set_border_width(20)
        self.message_label = Gtk.Label(label='Connecting to IWS…')
        self.retry = Gtk.Button(label='Retry')
        self.retry.set_name('iws-retry')
        self.retry.connect('clicked', lambda *_: self.begin_load(self.destination))
        self.message.pack_start(self.message_label, False, False, 0)
        self.message.pack_start(self.retry, False, False, 0)
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
        self.style.load_from_data(shell_css(dark).encode())
        self.connection_color = '#7fc9a4' if dark else '#2e6b4f'
        if self.state.state == 'ready':
            self.status.set_markup(f'<span foreground="{self.connection_color}">●</span>  Connected')

    def begin_load(self, uri):
        if not allowed_navigation(uri):
            return
        self.destination = uri
        if self.probing:
            return
        self.probing = True
        self.state.started()
        self.status.set_text('Connecting to IWS…')
        self.message_label.set_text('Connecting to IWS…')
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
            self.status.set_text('Loading IWS…')
        elif event == WebKit2.LoadEvent.FINISHED:
            self.state.finished()
            if self.state.state == 'ready':
                self.message.hide()
                self.status.set_markup(f'<span foreground="{self.connection_color}">●</span>  Connected')
            self.back.set_sensitive(self.web.can_go_back())

    def show_error(self, tls):
        self.state.failed(tls)
        self.status.set_text('IWS is unavailable')
        self.message_label.set_text('IWS could not verify this secure connection.' if tls
                                    else 'IWS is unavailable. Check your connection and try again.')
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
    IwsWindow()
    Gtk.main()

if __name__ == '__main__': main()
