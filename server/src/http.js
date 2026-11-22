'use strict';

/** Eroare cu cod HTTP si mesaj pentru utilizator (afisat ca atare in aplicatie). */
class ApiError extends Error {
  constructor(status, message, extra = {}) {
    super(message);
    this.status = status;
    this.extra = extra;
  }
}

/**
 * Router minimal: rute de forma "GET /post/:id". Fiecare handler primeste
 * { req, params, body, user } si intoarce { status, body } (sau arunca ApiError).
 */
class Router {
  constructor() {
    this.routes = [];
  }

  add(method, pattern, handler, { auth = false } = {}) {
    const keys = [];
    const regex = new RegExp(
      '^' +
        pattern.replace(/:([a-zA-Z]+)/g, (_, key) => {
          keys.push(key);
          return '([^/]+)';
        }) +
        '/?$',
    );
    this.routes.push({ method, regex, keys, handler, auth });
  }

  match(method, pathname) {
    let pathMatched = false;
    for (const route of this.routes) {
      const m = route.regex.exec(pathname);
      if (!m) continue;
      pathMatched = true;
      if (route.method !== method) continue;
      const params = {};
      route.keys.forEach((key, i) => (params[key] = decodeURIComponent(m[i + 1])));
      return { route, params };
    }
    return { route: null, pathMatched };
  }
}

/** Citeste corpul JSON al cererii, cu limita de marime. */
function readJsonBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > maxBytes) {
        reject(new ApiError(413, 'Datele trimise sunt prea mari (de exemplu, o poză prea mare).'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      // corpul exact, ca text: cererile semnate de tracker se verifica peste aceiasi octeti
      req.rawBody = Buffer.concat(chunks);
      if (size === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new ApiError(400, 'Cererea nu conține un JSON valid.'));
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, body, headers = {}) {
  if (status === 204) {
    res.writeHead(204, headers);
    res.end();
    return;
  }
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  res.end(payload);
}

function bearerToken(req) {
  const header = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(header);
  return match ? match[1].trim() : null;
}

module.exports = { ApiError, Router, readJsonBody, sendJson, bearerToken };
