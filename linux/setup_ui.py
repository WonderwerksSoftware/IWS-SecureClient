#!/usr/bin/python3 -I
"""Unprivileged native setup controller. It never embeds web content or performs network I/O."""
import json
import os
from pathlib import Path
import subprocess
import sys


STATUS = Path('/var/lib/iws-client-setup.json')
HELPER = '/usr/lib/iws-client/iws-setup-helper'
LAUNCH = ['/usr/bin/sudo', '-n', '--preserve-env=DISPLAY,WAYLAND_DISPLAY,XAUTHORITY',
          '/usr/bin/python3', '-I', '/usr/lib/iws-client/runtime.py', 'launch-existing']


def view_for(status):
    mode = status.get('mode') if isinstance(status, dict) else 'unknown'
    existing = bool(status.get('existingAvailable')) if isinstance(status, dict) else False
    views = {
        'fresh': ('Set up IWS', 'IWS is ready to be registered on this computer.', 'Set up', 'enroll', False),
        'enroll-retry': ('Set up IWS', 'Setup was interrupted. Retry with the protected installer registration.', 'Retry setup', 'enroll', False),
        'repair': ('Open or repair IWS', 'The existing IWS registration will be preserved.', 'Repair existing', 'repair', False),
        'replace': ('Replace IWS registration', 'This installer is for a different or newer registration. Replacing it requires explicit confirmation.', 'Replace registration', 'replace', True),
        'replace-retry': ('Finish replacing IWS registration', 'The new registration attempt was interrupted. The previous registration remains recoverable.', 'Retry setup', 'replace', True),
        'expired': ('Replacement installer required', 'This installer registration has expired or was rejected. Install a replacement IWS package. Any existing registration remains available.', None, None, False),
        'conflict': ('Newer IWS registration preserved', 'This installer is older than the existing registration. Install the correct replacement package.', None, None, False),
        'missing-installer': ('Set up IWS', 'Install a current IWS package to begin setup.', None, None, False),
        'unknown': ('IWS needs administrator assistance', 'IWS found transport state without a verified registration record and will not replace it automatically.', None, None, False),
    }
    title, message, button, action, confirm = views.get(mode, views['unknown'])
    return {'title': title, 'message': message, 'button': button, 'action': action,
            'confirm': confirm, 'canOpen': existing}


def helper_command(action):
    if action not in ('enroll', 'repair', 'replace'):
        raise ValueError('IWS_SETUP_ACTION_INVALID')
    return ['/usr/bin/pkexec', HELPER, action]


def read_status():
    try:
        value = json.loads(STATUS.read_text())
        return value if isinstance(value, dict) and value.get('schemaVersion') == 1 else {'mode': 'unknown'}
    except (OSError, json.JSONDecodeError):
        return {'mode': 'missing-installer'}


def refresh_view(status_reader=read_status):
    """Re-read root-published status after every privileged helper failure."""
    return view_for(status_reader())


def launch_existing():
    os.execv(LAUNCH[0], LAUNCH)


def main():
    status = read_status()
    if status.get('mode') == 'ready':
        launch_existing()
    import gi
    gi.require_version('Gtk', '3.0')
    from gi.repository import Gtk, GLib
    GLib.set_prgname('iws')
    GLib.set_application_name('IWS')
    view = view_for(status)
    window = Gtk.Window(title=view['title'])
    window.set_icon_name('iws')
    window.set_default_size(460, 190)
    window.set_resizable(False)
    window.set_position(Gtk.WindowPosition.CENTER)
    window.connect('destroy', Gtk.main_quit)
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
    box.set_border_width(24)
    heading = Gtk.Label()
    heading.set_markup(f"<b>{view['title']}</b>")
    heading.set_xalign(0)
    message = Gtk.Label(label=view['message'])
    message.set_line_wrap(True)
    message.set_xalign(0)
    result = Gtk.Label(label='')
    result.set_xalign(0)
    buttons = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
    box.pack_start(heading, False, False, 0)
    box.pack_start(message, True, True, 0)
    box.pack_start(result, False, False, 0)
    box.pack_end(buttons, False, False, 0)

    def open_iws(*_args):
        window.destroy()
        launch_existing()

    def act(*_args):
        nonlocal view
        if view['confirm']:
            dialog = Gtk.MessageDialog(transient_for=window, modal=True,
                message_type=Gtk.MessageType.WARNING, buttons=Gtk.ButtonsType.CANCEL,
                text='Replace the previous IWS registration?')
            dialog.format_secondary_text('The previous IWS state will be archived for administrator recovery.')
            dialog.add_button('Replace registration', Gtk.ResponseType.OK)
            response = dialog.run()
            dialog.destroy()
            if response != Gtk.ResponseType.OK:
                return
        result.set_text('Waiting for administrator approval…')
        while Gtk.events_pending():
            Gtk.main_iteration()
        completed = subprocess.run(helper_command(view['action']), check=False,
                                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
        if completed.returncode == 0:
            open_iws()
        else:
            view = refresh_view()
            render(view)
            result.set_text('IWS setup did not complete. Follow the updated guidance above.')

    open_button = Gtk.Button(label='Open existing IWS')
    open_button.connect('clicked', open_iws)
    buttons.pack_start(open_button, False, False, 0)
    action_button = Gtk.Button()
    action_button.connect('clicked', act)
    buttons.pack_end(action_button, False, False, 0)
    close = Gtk.Button(label='Close')
    close.connect('clicked', lambda *_: window.destroy())
    buttons.pack_end(close, False, False, 0)

    def render(updated):
        window.set_title(updated['title'])
        heading.set_markup(f"<b>{updated['title']}</b>")
        message.set_text(updated['message'])
        open_button.set_visible(updated['canOpen'])
        action_button.set_label(updated['button'] or '')
        action_button.set_visible(updated['action'] is not None)

    window.add(box)
    window.show_all()
    render(view)
    Gtk.main()


if __name__ == '__main__':
    if os.geteuid() == 0 or len(sys.argv) != 1:
        raise SystemExit('IWS_INVOCATION_INVALID')
    main()
