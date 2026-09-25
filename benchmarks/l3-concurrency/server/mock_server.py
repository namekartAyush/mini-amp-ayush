import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

class SlowHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        # 200 ms simulated delay
        time.sleep(0.200)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(b'{"status":"ok"}')

    def log_message(self, format, *args):
        # Suppress access logs for benchmark clarity
        pass

if __name__ == '__main__':
    port = 8085
    server = ThreadedHTTPServer(('127.0.0.1', port), SlowHandler)
    print(f"Slow mock HTTP server listening on http://127.0.0.1:{port} (200ms delay per request)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()
