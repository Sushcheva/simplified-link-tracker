"""Small HTTP/1.1 client with persistent connections, cookies and CSRF for verification."""
import http.client
import json
from http.cookies import SimpleCookie
from urllib.parse import urlencode, urlsplit

class Client:
    def __init__(self, origins):
        self.origins = origins
        self.connections = {}
        self.cookies = {}
        self.csrf = None
        self.sequence = 0

    def request(self, method, path, data=None, expected=200, server=None, csrf=True):
        index = self.sequence % len(self.origins) if server is None else server
        self.sequence += 1
        origin = self.origins[index]
        unsafe = method not in ('GET', 'HEAD', 'OPTIONS')
        if unsafe and csrf and not self.csrf: self.refresh_csrf(server=index)
        headers = {}
        if self.cookies: headers['Cookie'] = '; '.join(f'{k}={v}' for k,v in self.cookies.items())
        if unsafe and csrf: headers[self.csrf['headerName']] = self.csrf['token']
        form = path == '/api/auth/login'
        body = None if data is None else (urlencode(data) if form else json.dumps(data)).encode()
        if body is not None: headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        if origin not in self.connections:
            url = urlsplit(origin)
            self.connections[origin] = http.client.HTTPConnection(url.hostname,url.port,timeout=20)
        conn = self.connections[origin]
        try:
            conn.request(method,path,body,headers)
            response = conn.getresponse()
            raw = response.read()
            for name,value in response.getheaders():
                if name.lower() == 'set-cookie':
                    parsed = SimpleCookie(); parsed.load(value)
                    for key,cookie in parsed.items():
                        if cookie['max-age'] == '0': self.cookies.pop(key,None)
                        else: self.cookies[key]=cookie.value
            if expected is not None and response.status != expected:
                raise AssertionError(f'{method} {path}: HTTP {response.status}, expected {expected}: {raw[:160]!r}')
            return json.loads(raw) if raw and 'json' in response.getheader('Content-Type','') else raw
        except Exception:
            conn.close(); self.connections.pop(origin,None)
            raise

    def refresh_csrf(self,server=None):
        self.csrf = self.request('GET','/api/auth/csrf',server=server)

    def register(self,email,password):
        self.request('POST','/api/auth/register',{'email':email,'password':password},201)
        self.request('POST','/api/auth/login',{'email':email,'password':password})
        self.refresh_csrf()

    def close(self):
        for conn in self.connections.values(): conn.close()
        self.connections.clear()
