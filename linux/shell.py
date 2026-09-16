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

class IwsWindow(Gtk.Window):
    def __init__(self):
        super().__init__(title='IWS')
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
        rail.set_border_width(8)
        self.back = Gtk.Button(label='Back')
        self.back.set_sensitive(False)
        self.back.connect('clicked', lambda *_: self.web.go_back() if self.web.can_go_back() else None)
        self.portal = Gtk.Button(label='IWS Portal')
        self.portal.connect('clicked', self.go_home)
        self.status = Gtk.Label(label='Connecting to IWS…')
        rail.pack_start(self.back, False, False, 0)
        rail.pack_start(self.portal, False, False, 0)
        rail.pack_end(self.status, False, False, 0)
        layout.pack_start(rail, False, False, 0)
        layout.pack_start(Gtk.Separator(), False, False, 0)
        overlay = Gtk.Overlay()
        overlay.add(self.web)
        self.message = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.message.set_halign(Gtk.Align.CENTER)
        self.message.set_valign(Gtk.Align.CENTER)
        self.message.set_border_width(20)
        self.message_label = Gtk.Label(label='Connecting to IWS…')
        self.retry = Gtk.Button(label='Retry')
        self.retry.connect('clicked', lambda *_: self.begin_load(self.destination))
        self.message.pack_start(self.message_label, False, False, 0)
        self.message.pack_start(self.retry, False, False, 0)
        overlay.add_overlay(self.message)
        layout.pack_start(overlay, True, True, 0)
        self.add(layout)
        self.show_all()
        self.begin_load(PORTAL)

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
                self.status.set_text('IWS')
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
