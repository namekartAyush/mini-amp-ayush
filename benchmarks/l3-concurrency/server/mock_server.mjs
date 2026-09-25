import http from 'node:http';

const PORT = 8085;

const server = http.createServer((req, res) => {
    // 200 ms simulated server latency
    setTimeout(() => {
        res.writeHead(200, {
            'Content-Type': 'application/json',
            'Connection': 'keep-alive'
        });
        res.end(JSON.stringify({ status: 'ok' }));
    }, 200);
});

// Windows default backlog is small, set to 512 for burst requests
server.keepAliveTimeout = 60000;
server.headersTimeout = 65000;
server.requestTimeout = 60000;

server.listen(PORT, '127.0.0.1', 512, () => {
    console.log(`Node mock server listening on http://127.0.0.1:${PORT} (200ms delay per request)`);
});

