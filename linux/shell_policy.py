from urllib.parse import urlsplit

PORTAL = 'https://portal.iws.internal/'

def allowed_navigation(uri):
    try:
        value = urlsplit(uri)
        return (value.scheme == 'https' and value.hostname == 'portal.iws.internal'
                and value.port in (None, 443) and value.username is None
                and value.password is None and '\\' not in uri
                and not any(ord(c) < 32 for c in uri))
    except (TypeError, ValueError):
        return False

class LoadState:
    def __init__(self):
        self.state = 'loading'
    def started(self):
        self.state = 'loading'
    def failed(self, tls=False):
        if self.state != 'certificate-error':
            self.state = 'certificate-error' if tls else 'unavailable'
    def finished(self):
        if self.state == 'loading':
            self.state = 'ready'
