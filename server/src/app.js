'use strict';

const crypto = require('node:crypto');
const http = require('node:http');
const config = require('./config');
const { Store } = require('./store');
const { WsHub } = require('./websocket');
const { ApiError, Router, readJsonBody, sendJson, bearerToken } = require('./http');
const security = require('./security');

const ROLES = ['USER', 'ADMIN'];
const PROVIDERS = { EMAIL: 'EMAIL', GOOGLE: 'GOOGLE', APPLE: 'APPLE', ANONYMOUS: 'ANONYMOUS' };
const INVALID_CREDENTIALS = 'E-mail sau parolă incorecte.';
const BLOCKED = 'Acest cont a fost blocat de un administrator.';

/**
 * Construieste serverul (HTTP + WebSocket). Separat de server.js ca testele sa poata
 * porni un server pe un port aleator, cu o baza de date temporara.
 */
function createApp(options = {}) {
  const settings = { ...config, ...options };
  const store = new Store(settings.dataDir);
  const limiter = new security.LoginLimiter(settings.login);
  const router = new Router();

  // ---------------------------------------------------------------------------
  // Autentificare
  // ---------------------------------------------------------------------------

  /** Utilizatorul din token (sau null): semnatura valida, neexpirat, nerevocat, cont existent. */
  function userFromToken(token) {
    const claims = security.verifyToken(token, store.secret);
    if (!claims || store.isRevoked(claims.jti)) return null;
    const user = store.findUserById(claims.sub);
    if (!user || user.blocked) return null;
    return { user, claims };
  }

  const hub = new WsHub((token) => {
    const session = userFromToken(token);
    return session ? session.user : null;
  });

  function issueToken(user) {
    return security.signToken({ sub: user.id, role: user.role }, store.secret, settings.tokenTtlSeconds);
  }

  /** Raspunsul la orice login reusit: token-ul + utilizatorul (fara hash-ul parolei). */
  function authResponse(user) {
    return {
      token: issueToken(user),
      // "password" e mereu gol: aplicatia are clasa User(email, password, id)
      user: { email: user.email, password: '', id: user.id },
      role: user.role,
      provider: user.provider,
    };
  }

  function publicUser(user) {
    return {
      id: user.id,
      email: user.email,
      role: user.role,
      provider: user.provider,
      blocked: user.blocked,
      createdAt: user.createdAt,
    };
  }

  function requireAdmin(user) {
    if (user.role !== 'ADMIN') throw new ApiError(403, 'Doar administratorii pot face această operație.');
  }

  router.add('GET', '/health', () => ({ status: 200, body: { status: 'ok', time: new Date().toISOString() } }));

  router.add('POST', '/signup', ({ body }) => {
    const email = security.normalizeEmail(body.email);
    const password = typeof body.password === 'string' ? body.password : '';
    if (!security.isValidEmail(email)) throw new ApiError(400, 'Format de e-mail invalid.');
    const problems = security.passwordProblems(password, email);
    if (problems.length) {
      throw new ApiError(400, `Parola nu respectă cerințele: ${problems.join(', ')}.`, { problems });
    }
    if (store.findUserByEmail(email)) {
      throw new ApiError(409, 'Există deja un cont cu acest e-mail. Folosiți tab-ul Autentificare.');
    }
    const user = store.createUser({
      email,
      passwordHash: security.hashPassword(password),
      provider: PROVIDERS.EMAIL,
      role: settings.adminEmails.includes(email) ? 'ADMIN' : 'USER',
    });
    return { status: 201, body: authResponse(user) };
  });

  router.add('POST', '/login', ({ body }) => {
    const email = security.normalizeEmail(body.email);
    const password = typeof body.password === 'string' ? body.password : '';
    if (!email || !password) throw new ApiError(400, 'Introduceți e-mailul și parola.');

    const lockMs = limiter.remainingLockMs(email);
    if (lockMs > 0) throw tooManyAttempts(lockMs);

    const user = store.findUserByEmail(email);
    // verificam si cand contul nu exista (hash fals), ca durata sa nu dezvaluie conturile
    const ok = security.verifyPassword(password, user && user.passwordHash ? user.passwordHash : security.DUMMY_HASH);
    if (!user || !user.passwordHash || !ok) {
      const lockedFor = limiter.registerFailure(email);
      if (lockedFor > 0) throw tooManyAttempts(lockedFor);
      const left = limiter.attemptsLeft(email);
      throw new ApiError(401, INVALID_CREDENTIALS, { attemptsLeft: left });
    }
    if (user.blocked) throw new ApiError(403, BLOCKED);
    limiter.registerSuccess(email);
    return { status: 200, body: authResponse(user) };
  });

  function tooManyAttempts(lockMs) {
    const seconds = Math.ceil(lockMs / 1000);
    return new ApiError(
      429,
      `Prea multe încercări greșite. Din motive de securitate, puteți încerca din nou peste ${seconds} s.`,
      { retryAfterSeconds: seconds, headers: { 'Retry-After': String(seconds) } },
    );
  }

  /**
   * Google / Apple SIMULAT (tema nu cere un cont real de dezvoltator Google / Apple):
   * aplicatia trimite adresa contului ales, iar serverul creeaza / incarca contul si da token.
   * Ca sa nu poata fi preluat un cont cu parola, adresele care au deja cont cu parola
   * (sau cu alt furnizor) sunt refuzate. Conturile create astfel nu devin niciodata ADMIN.
   */
  router.add('POST', '/auth/provider', ({ body }) => {
    const provider = String(body.provider || '').toUpperCase();
    if (provider !== PROVIDERS.GOOGLE && provider !== PROVIDERS.APPLE) {
      throw new ApiError(400, 'Furnizor necunoscut. Folosiți Google sau Apple.');
    }
    const email = security.normalizeEmail(body.email);
    if (!security.isValidEmail(email)) throw new ApiError(400, 'Format de e-mail invalid.');
    let user = store.findUserByEmail(email);
    if (user && user.provider !== provider) {
      const how = user.provider === PROVIDERS.EMAIL ? 'e-mail și parolă' : user.provider === PROVIDERS.GOOGLE ? 'Google' : 'Apple';
      throw new ApiError(409, `Această adresă are deja un cont creat cu ${how}. Conectați-vă cu ${how}.`);
    }
    if (user && user.blocked) throw new ApiError(403, BLOCKED);
    if (!user) user = store.createUser({ email, provider, role: 'USER' });
    return { status: 200, body: authResponse(user) };
  });

  /** Vizitator anonim: un cont fara date personale, legat de un id aleator generat pe telefon. */
  router.add('POST', '/auth/anonymous', ({ body }) => {
    const guestId = String(body.guestId || '');
    if (!/^[a-zA-Z0-9-]{4,64}$/.test(guestId)) throw new ApiError(400, 'Identificator de vizitator invalid.');
    const email = `vizitator-${guestId.toLowerCase()}@anonim.local`;
    let user = store.findUserByEmail(email);
    if (user && user.blocked) throw new ApiError(403, BLOCKED);
    if (!user) user = store.createUser({ email, provider: PROVIDERS.ANONYMOUS, role: 'USER' });
    return { status: 200, body: authResponse(user) };
  });

  router.add('GET', '/me', ({ user }) => ({ status: 200, body: publicUser(user) }), { auth: true });

  /** Logout: token-ul curent e revocat pe server (nu mai poate fi folosit, chiar daca a fost copiat). */
  router.add(
    'POST',
    '/logout',
    ({ claims }) => {
      store.revokeToken(claims.jti, claims.exp);
      return { status: 204 };
    },
    { auth: true },
  );

  // ---------------------------------------------------------------------------
  // Postari (CRUD)
  // ---------------------------------------------------------------------------

  /** Locatia vine ca obiect {latitude, longitude} sau ca text "lat,lng"; o pastram ca text. */
  function normalizeLocation(location) {
    let lat = 0;
    let lng = 0;
    if (location && typeof location === 'object') {
      lat = Number(location.latitude);
      lng = Number(location.longitude);
    } else if (typeof location === 'string') {
      [lat, lng] = location.split(',').map((v) => Number(v.trim()));
    }
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
      return '0,0';
    }
    return `${lat},${lng}`;
  }

  function validatePostInput(body) {
    const description = typeof body.description === 'string' ? body.description.trim() : '';
    const photo = typeof body.photo === 'string' ? body.photo : '';
    if (!description && !photo) throw new ApiError(400, 'Postarea trebuie să aibă un text sau o poză.');
    if (description.length > 2000) throw new ApiError(400, 'Textul postării poate avea cel mult 2000 de caractere.');
    // aplicatia micsoreaza pozele (~350 KB); limita protejeaza baza de date de pe telefoane
    // (SQLite pe Android nu poate citi un rand mai mare de ~2 MB)
    if (photo.length > 1_000_000) throw new ApiError(413, 'Poza este prea mare. Alegeți o poză mai mică.');
    return { description, photo, location: normalizeLocation(body.location) };
  }

  function requireAuthor(post, user) {
    if (post.user_id !== String(user.id)) throw new ApiError(403, 'Puteți modifica doar postările proprii.');
  }

  function findPostOr404(id) {
    const post = store.findPost(id);
    if (!post) throw new ApiError(404, 'Postarea nu mai există. Poate a fost ștearsă între timp.');
    return post;
  }

  router.add('GET', '/posts', () => ({ status: 200, body: store.listPosts() }), { auth: true });

  router.add('GET', '/post/:id', ({ params }) => ({ status: 200, body: findPostOr404(params.id) }), { auth: true });

  router.add(
    'POST',
    '/post',
    ({ body, user }) => {
      // doar USER publica: adminul modereaza, vizitatorul anonim doar citeste (ca in aplicatie)
      if (user.role !== 'USER' || user.provider === PROVIDERS.ANONYMOUS) {
        throw new ApiError(403, user.role === 'ADMIN'
          ? 'Administratorii nu pot publica postări.'
          : 'Vizitatorii anonimi nu pot publica postări. Creați un cont.');
      }
      const input = validatePostInput(body);
      const now = new Date().toISOString();
      const post = {
        id: crypto.randomUUID(),
        ...input,
        // autorul vine din token, nu din ce trimite aplicatia
        user_id: String(user.id),
        user_profile_photo: '',
        user_last_name: '',
        user_first_name: user.email.split('@')[0],
        professional: false,
        created_at: now,
        updated_at: now,
      };
      store.insertPost(post);
      hub.broadcast('created', post);
      return { status: 201, body: post };
    },
    { auth: true },
  );

  router.add(
    'PUT',
    '/post/:id',
    ({ params, body, user }) => {
      const existing = findPostOr404(params.id);
      requireAuthor(existing, user);
      const input = validatePostInput({ ...existing, ...body });
      const updated = { ...existing, ...input, updated_at: new Date().toISOString() };
      store.replacePost(updated);
      hub.broadcast('updated', updated);
      return { status: 200, body: updated };
    },
    { auth: true },
  );

  router.add(
    'DELETE',
    '/post/:id',
    ({ params, user }) => {
      const existing = findPostOr404(params.id);
      if (user.role !== 'ADMIN') requireAuthor(existing, user);
      store.deletePost(existing.id);
      hub.broadcast('deleted', existing);
      return { status: 204 };
    },
    { auth: true },
  );

  // ---------------------------------------------------------------------------
  // Utilizatori (doar ADMIN): roluri si blocare
  // ---------------------------------------------------------------------------

  router.add(
    'GET',
    '/users',
    ({ user }) => {
      requireAdmin(user);
      return { status: 200, body: store.listUsers().map(publicUser) };
    },
    { auth: true },
  );

  router.add(
    'PATCH',
    '/users/:id',
    ({ params, body, user }) => {
      requireAdmin(user);
      const target = store.findUserById(Number(params.id));
      if (!target) throw new ApiError(404, 'Utilizatorul nu mai există.');
      const changes = {};

      if (body.role !== undefined) {
        if (!ROLES.includes(body.role)) throw new ApiError(400, 'Rol necunoscut.');
        if (body.role === 'ADMIN' && target.provider === PROVIDERS.ANONYMOUS) {
          throw new ApiError(400, 'Un vizitator anonim nu poate deveni administrator.');
        }
        if (target.role === 'ADMIN' && body.role === 'USER' && !target.blocked && store.countActiveAdmins() <= 1) {
          throw new ApiError(409, 'Trebuie să rămână cel puțin un administrator activ.');
        }
        changes.role = body.role;
      }

      if (body.blocked !== undefined) {
        if (typeof body.blocked !== 'boolean') throw new ApiError(400, 'Câmpul „blocked” trebuie să fie true sau false.');
        if (target.id === user.id && body.blocked) throw new ApiError(400, 'Nu vă puteți bloca propriul cont.');
        if (body.blocked && target.role === 'ADMIN' && !target.blocked && store.countActiveAdmins() <= 1) {
          throw new ApiError(409, 'Trebuie să rămână cel puțin un administrator activ.');
        }
        changes.blocked = body.blocked;
      }

      return { status: 200, body: publicUser(store.updateUser(target.id, changes)) };
    },
    { auth: true },
  );

  // ---------------------------------------------------------------------------
  // Serverul HTTP
  // ---------------------------------------------------------------------------

  const server = http.createServer(async (req, res) => {
    const started = Date.now();
    const { pathname } = new URL(req.url, 'http://localhost');
    let status = 500;
    try {
      const { route, params, pathMatched } = router.match(req.method, pathname);
      if (!route) {
        throw pathMatched
          ? new ApiError(405, 'Metodă nepermisă pentru această adresă.')
          : new ApiError(404, 'Adresa cerută nu există pe server.');
      }

      let session = null;
      if (route.auth) {
        const token = bearerToken(req);
        if (!token) throw new ApiError(401, 'Autentificare necesară. Conectați-vă din nou.');
        session = userFromToken(token);
        if (!session) {
          // token expirat / revocat / invalid, sau cont blocat intre timp
          const claims = security.verifyToken(token, store.secret);
          const blocked = claims && store.findUserById(claims.sub)?.blocked;
          throw blocked
            ? new ApiError(403, BLOCKED)
            : new ApiError(401, 'Sesiunea a expirat. Conectați-vă din nou.');
        }
      }

      const body = ['POST', 'PUT', 'PATCH'].includes(req.method)
        ? await readJsonBody(req, settings.maxBodyBytes)
        : {};
      const result = await route.handler({
        req,
        params,
        body: body && typeof body === 'object' ? body : {},
        user: session && session.user,
        claims: session && session.claims,
      });
      status = result.status;
      sendJson(res, result.status, result.body);
    } catch (error) {
      if (error instanceof ApiError) {
        status = error.status;
        const { headers, ...extra } = error.extra;
        sendJson(res, error.status, { message: error.message, ...extra }, headers);
      } else {
        console.error(error);
        status = 500;
        sendJson(res, 500, { message: 'Eroare internă a serverului. Încercați din nou mai târziu.' });
      }
    } finally {
      if (!settings.quiet) console.log(`${req.method} ${pathname} -> ${status} (${Date.now() - started} ms)`);
    }
  });

  server.on('upgrade', (req, socket, head) => hub.handleUpgrade(req, socket, head));

  function close() {
    hub.closeAll();
    return new Promise((resolve) => server.close(() => resolve()));
  }

  return { server, store, hub, close };
}

module.exports = { createApp };
