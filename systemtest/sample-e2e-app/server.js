/*
 * Minimal HTTP server used by the AI-Git-Bot M4 wave-2 E2E recipe.
 *
 * Endpoints:
 *   GET  /            – HTML login form (renders sign-in fields)
 *   POST /login       – accepts demo/demo, otherwise returns 401
 *   GET  /dashboard   – renders only when ?token=ok query is present
 *   GET  /healthz     – probe target for the StaticPreviewUrlStrategy and
 *                       the container HEALTHCHECK
 *
 * No external dependencies on purpose: the npm install step is avoided
 * so the docker build stays fast and the container starts in <1s on
 * a developer laptop.
 */
const http = require('http');

const PORT = process.env.PORT ? Number(process.env.PORT) : 3000;

const html = (title, body) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>${title}</title></head>
<body><h1>${title}</h1>${body}</body></html>`;

const loginForm = html('Sign in',
    `<form method="POST" action="/login">
       <label>User <input name="user" id="user"/></label>
       <label>Password <input type="password" name="pass" id="pass"/></label>
       <button type="submit" id="submit">Sign in</button>
     </form>`);

function parseForm(body) {
    const params = new URLSearchParams(body);
    return { user: params.get('user'), pass: params.get('pass') };
}

const server = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end(loginForm);
    }
    if (req.method === 'GET' && req.url === '/healthz') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end('{"status":"ok"}');
    }
    if (req.method === 'POST' && req.url === '/login') {
        let body = '';
        req.on('data', (chunk) => { body += chunk; });
        req.on('end', () => {
            const { user, pass } = parseForm(body);
            if (user === 'demo' && pass === 'demo') {
                res.writeHead(302, { Location: '/dashboard?token=ok' });
                return res.end();
            }
            res.writeHead(401, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(html('Sign in failed', '<p id="error">Invalid credentials.</p>'));
        });
        return;
    }
    if (req.method === 'GET' && req.url.startsWith('/dashboard')) {
        const ok = req.url.includes('token=ok');
        res.writeHead(ok ? 200 : 403, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end(html(ok ? 'Welcome' : 'Forbidden',
            ok ? '<p id="welcome">Welcome, demo!</p>'
               : '<p>Please sign in first.</p>'));
    }
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found');
});

server.listen(PORT, () => {
    console.log(`sample-e2e-app listening on http://0.0.0.0:${PORT}`);
});

