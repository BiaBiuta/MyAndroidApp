'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const config = require('./config');
const { Store } = require('./store');
const { WsHub } = require('./websocket');
const { ApiError, Router, readJsonBody, readRawBody, sendJson, bearerToken } = require('./http');
const security = require('./security');
const { registerDeviceRoutes } = require('./devices');

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

  router.add('GET', '/health', () => ({ status: 200, body: { status: 'ok', version: 6, features: ['title', 'rating', 'visit_date', 'drafts', 'place', 'moderation', 'stats', 'devices', 'photos', 'likes', 'comments', 'replies', 'trips', 'follows', 'gallery'], time: new Date().toISOString() } }));

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
  // Postari (CRUD + sincronizare offline)
  //
  //  - id-ul postarii poate fi generat pe telefon (UUID), deci o postare creata offline
  //    are acelasi id peste tot; PUT /post/:id creeaza SAU actualizeaza (upsert), asa ca
  //    sincronizarea trimite acelasi apel indiferent daca postarea exista deja pe server;
  //  - conflicte: castiga modificarea cea mai recenta (updated_at); una mai veche primeste 409
  //    cu versiunea de pe server;
  //  - o postare stearsa (de autor sau de moderator) raspunde 410 la editare, ca telefonul
  //    sa nu o recreeze din coada offline;
  //  - DRAFT: vizibila doar autorului; ascunsa de admin (hidden): vizibila autorului si adminilor.
  // ---------------------------------------------------------------------------

  const STATUSES = ['ACTIVE', 'DRAFT'];
  const PHOTO_URL = /^\/photos\/[a-zA-Z0-9-]{8,64}$/;
  const ID_PATTERN = /^[a-zA-Z0-9-]{8,64}$/;

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
    const title = typeof body.title === 'string' ? body.title.trim() : '';
    const description = typeof body.description === 'string' ? body.description.trim() : '';
    const photo = typeof body.photo === 'string' ? body.photo : '';
    // poza urcata separat (PUT /photos/:id): postarea pastreaza doar adresa ei
    const photoUrl = typeof body.photo_url === 'string' ? body.photo_url : '';
    if (photoUrl && !PHOTO_URL.test(photoUrl)) throw new ApiError(400, 'Adresa pozei nu este validă.');
    if (!title && !description && !photo && !photoUrl && !(Array.isArray(body.gallery) && body.gallery.length)) {
      throw new ApiError(400, 'Postarea trebuie să aibă un titlu, un text sau o poză.');
    }
    if (title.length > 120) throw new ApiError(400, 'Titlul poate avea cel mult 120 de caractere.');
    if (description.length > 2000) throw new ApiError(400, 'Textul postării poate avea cel mult 2000 de caractere.');
    // aplicatia micsoreaza pozele (~350 KB); limita protejeaza baza de date de pe telefoane
    // (SQLite pe Android nu poate citi un rand mai mare de ~2 MB)
    if (photo.length > 1_000_000) throw new ApiError(413, 'Poza este prea mare. Alegeți o poză mai mică.');
    const rating = Number(body.rating ?? 0);
    if (!Number.isInteger(rating) || rating < 0 || rating > 5) {
      throw new ApiError(400, 'Ratingul trebuie să fie între 1 și 5 stele.');
    }
    const visitDate = Number(body.visit_date ?? 0);
    if (!Number.isFinite(visitDate) || visitDate < 0) throw new ApiError(400, 'Data vizitei nu este validă.');
    const status = body.status === undefined || body.status === null || body.status === '' ? 'ACTIVE' : body.status;
    if (!STATUSES.includes(status)) throw new ApiError(400, 'Starea postării trebuie să fie ACTIVE sau DRAFT.');
    const place = typeof body.place === 'string' ? body.place.trim() : '';
    if (place.length > 160) throw new ApiError(400, 'Numele locului poate avea cel mult 160 de caractere.');
    // pozele in plus (galerie): doar adrese de poze urcate, maxim 5 (cu poza principala = 6)
    const gallery = Array.isArray(body.gallery) ? body.gallery : [];
    if (gallery.length > 5) throw new ApiError(400, 'O postare poate avea cel mult 6 poze.');
    if (gallery.some((u) => typeof u !== 'string' || !PHOTO_URL.test(u))) throw new ApiError(400, 'Adresa unei poze nu este validă.');
    const tripId = typeof body.trip_id === 'string' ? body.trip_id : '';
    if (tripId && !ID_PATTERN.test(tripId)) throw new ApiError(400, 'Călătoria nu este validă.');
    return { title, description, photo, photo_url: photoUrl, gallery, trip_id: tripId, place, location: normalizeLocation(body.location), rating, visit_date: visitDate, status };
  }

  /** Ce vede un utilizator: postarile active si neascunse; ale lui oricum; adminul si pe cele ascunse. */
  function canSee(post, user) {
    if (!user) return false;
    if (post.user_id === String(user.id)) return true;
    if (post.status === 'DRAFT') return false;
    return !post.hidden || user.role === 'ADMIN';
  }

  /** Anunta prin WebSocket fiecare client cu ce poate vedea (altfel "deleted", ca sa o scoata din lista). */
  function broadcastPost(event, post) {
    hub.broadcastWith((viewer) => {
      if (event === 'deleted' || !canSee(post, viewer)) return { event: 'deleted', payload: { item: post } };
      return { event, payload: { item: forViewer(post, viewer) } };
    });
  }

  store.data.photos = store.data.photos || [];
  store.data.likes = store.data.likes || [];
  store.data.comments = store.data.comments || [];
  store.data.commentLikes = store.data.commentLikes || [];
  store.data.trips = store.data.trips || [];
  store.data.follows = store.data.follows || [];

  /** Postarea, cum o vede un anumit utilizator: cu numarul de aprecieri / comentarii si daca a apreciat-o. */
  function forViewer(post, viewer) {
    const likes = store.data.likes.filter((l) => l.post_id === post.id);
    return {
      ...post,
      like_count: likes.length,
      liked_by_me: Boolean(viewer) && likes.some((l) => l.user_id === String(viewer.id)),
      comment_count: store.data.comments.filter((c) => c.post_id === post.id).length,
    };
  }

  /** Poza urcata trebuie sa fie a autorului postarii. */
  /** Toate pozele postarii trebuie sa fie ale autorului, iar calatoria (daca e) tot a lui. */
  function requireOwnMedia(input, user) {
    requireOwnPhoto(input.photo_url, user);
    for (const url of input.gallery || []) requireOwnPhoto(url, user);
    if (input.trip_id) {
      const trip = store.data.trips.find((t) => t.id === input.trip_id && !t.deleted);
      if (!trip || trip.owner_id !== String(user.id)) throw new ApiError(400, 'Călătoria nu există. Sincronizați întâi călătoria.');
    }
  }

  function requireOwnPhoto(photoUrl, user) {
    if (!photoUrl) return;
    const id = photoUrl.slice('/photos/'.length);
    const photo = store.data.photos.find((p) => p.id === id);
    if (!photo || photo.owner_id !== String(user.id)) {
      throw new ApiError(400, 'Poza postării nu a fost găsită. Încărcați-o din nou.');
    }
  }

  function requireAuthor(post, user) {
    if (post.user_id !== String(user.id)) throw new ApiError(403, 'Puteți modifica doar postările proprii.');
  }

  function requirePublisher(user) {
    // doar USER publica: adminul modereaza, vizitatorul anonim doar citeste (ca in aplicatie)
    if (user.role !== 'USER' || user.provider === PROVIDERS.ANONYMOUS) {
      throw new ApiError(403, user.role === 'ADMIN'
        ? 'Administratorii nu pot publica postări.'
        : 'Vizitatorii anonimi nu pot publica postări. Creați un cont.');
    }
  }

  function findVisiblePostOr404(id, user) {
    const post = store.findPost(id);
    if (!post || !canSee(post, user)) throw new ApiError(404, 'Postarea nu mai există. Poate a fost ștearsă între timp.');
    return post;
  }

  function goneError(id) {
    const tombstone = store.findTombstone(id);
    return new ApiError(
      410,
      tombstone && tombstone.byModerator
        ? 'Această postare a fost ștearsă de un moderator.'
        : 'Această postare a fost ștearsă.',
      { removedBy: tombstone && tombstone.byModerator ? 'MODERATOR' : 'OWNER' },
    );
  }

  /** Data unei modificari trimise de telefon (momentul editarii, si offline); nu in viitor. */
  function editTime(value) {
    const time = Date.parse(value);
    const now = Date.now();
    return new Date(Number.isFinite(time) ? Math.min(time, now) : now).toISOString();
  }

  function createPost(id, input, user, updatedAt) {
    requireOwnMedia(input, user);
    const now = new Date().toISOString();
    const post = {
      id,
      ...input,
      hidden: false,
      // autorul vine din token, nu din ce trimite aplicatia
      user_id: String(user.id),
      user_profile_photo: '',
      user_last_name: '',
      user_first_name: user.email.split('@')[0],
      professional: false,
      created_at: now,
      updated_at: updatedAt || now,
    };
    store.insertPost(post);
    broadcastPost('created', post);
    return post;
  }

  router.add(
    'GET',
    '/posts',
    ({ user }) => ({ status: 200, body: store.listPosts().filter((p) => canSee(p, user)).map((p) => forViewer(p, user)) }),
    { auth: true },
  );

  router.add('GET', '/post/:id', ({ params, user }) => ({ status: 200, body: forViewer(findVisiblePostOr404(params.id, user), user) }), {
    auth: true,
  });

  /** Postare noua. Telefonul poate trimite propriul id (UUID); retrimiterea aceluiasi id nu dubleaza postarea. */
  router.add(
    'POST',
    '/post',
    ({ body, user }) => {
      requirePublisher(user);
      const input = validatePostInput(body);
      const id = typeof body.id === 'string' && ID_PATTERN.test(body.id) ? body.id : crypto.randomUUID();
      if (store.findTombstone(id)) throw goneError(id);
      const existing = store.findPost(id);
      if (existing) {
        requireAuthor(existing, user);
        return { status: 200, body: forViewer(existing, user) }; // trimisa deja (ex. raspunsul s-a pierdut pe drum)
      }
      return { status: 201, body: forViewer(createPost(id, input, user, editTime(body.updated_at)), user) };
    },
    { auth: true },
  );

  /** Creeaza sau actualizeaza (upsert), cu "ultima modificare castiga". */
  router.add(
    'PUT',
    '/post/:id',
    ({ params, body, user }) => {
      if (!ID_PATTERN.test(params.id)) throw new ApiError(400, 'Identificator de postare invalid.');
      if (store.findTombstone(params.id)) throw goneError(params.id);
      const existing = store.findPost(params.id);
      const updatedAt = editTime(body.updated_at);

      if (!existing) {
        requirePublisher(user);
        return { status: 201, body: forViewer(createPost(params.id, validatePostInput(body), user, updatedAt), user) };
      }

      requireAuthor(existing, user);
      if (Date.parse(updatedAt) < Date.parse(existing.updated_at)) {
        // pe alt dispozitiv s-a facut o modificare mai noua: o pastram pe aceea
        throw new ApiError(409, 'Această postare a fost modificată pe alt dispozitiv. Se păstrează varianta mai nouă.', {
          item: forViewer(existing, user),
        });
      }
      const input = validatePostInput({ ...existing, ...body });
      requireOwnMedia(input, user);
      const updated = { ...existing, ...input, updated_at: updatedAt };
      store.replacePost(updated);
      broadcastPost('updated', updated);
      return { status: 200, body: forViewer(updated, user) };
    },
    { auth: true },
  );

  router.add(
    'DELETE',
    '/post/:id',
    ({ params, user }) => {
      const existing = store.findPost(params.id);
      if (!existing) {
        if (store.findTombstone(params.id)) return { status: 204 }; // deja stearsa: succes
        throw new ApiError(404, 'Postarea nu mai există.');
      }
      const isOwner = existing.user_id === String(user.id);
      if (user.role !== 'ADMIN') requireAuthor(existing, user);
      store.deletePost(existing.id, { byModerator: !isOwner });
      // aprecierile si comentariile postarii dispar odata cu ea
      store.data.likes = store.data.likes.filter((l) => l.post_id !== existing.id);
      const goneComments = new Set(store.data.comments.filter((c) => c.post_id === existing.id).map((c) => c.id));
      store.data.comments = store.data.comments.filter((c) => c.post_id !== existing.id);
      store.data.commentLikes = store.data.commentLikes.filter((l) => !goneComments.has(l.comment_id));
      store.save();
      broadcastPost('deleted', existing);
      return { status: 204 };
    },
    { auth: true },
  );


  // ---------------------------------------------------------------------------
  // Poze (fisiere separate de postari)
  //
  //  - telefonul urca poza INAINTE de postare: PUT /photos/:id (id = UUID generat pe telefon,
  //    deci reincercarea dupa o conexiune cazuta nu dubleaza poza); corpul = JPEG / PNG / WebP;
  //  - postarea primeste doar adresa ("/photos/<id>"), nu poza in sine;
  //  - GET /photos/:id: doar autorul pozei sau cine poate vedea o postare care o foloseste
  //    (poza unei ciorne nu e vizibila altora).
  // ---------------------------------------------------------------------------

  const photosDir = path.join(settings.dataDir, 'photos');
  const MAX_PHOTO_BYTES = settings.maxPhotoBytes || 3 * 1024 * 1024;

  function imageType(buffer) {
    if (buffer.length > 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) return 'image/jpeg';
    if (buffer.length > 8 && buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png';
    if (buffer.length > 12 && buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
    return null;
  }

  router.add(
    'PUT',
    '/photos/:id',
    async ({ req, params, user }) => {
      requirePublisher(user);
      if (!ID_PATTERN.test(params.id)) throw new ApiError(400, 'Identificator de poză invalid.');
      const existing = store.data.photos.find((p) => p.id === params.id);
      if (existing && existing.owner_id !== String(user.id)) throw new ApiError(409, 'Identificatorul pozei este deja folosit.');
      const data = await readRawBody(req, MAX_PHOTO_BYTES);
      const type = imageType(data);
      // verificam continutul, nu doar ce declara clientul
      if (!type) throw new ApiError(415, 'Fișierul nu este o poză (JPEG, PNG sau WebP).');
      fs.mkdirSync(photosDir, { recursive: true });
      fs.writeFileSync(path.join(photosDir, params.id), data);
      if (existing) {
        existing.size = data.length;
        existing.type = type;
      } else {
        store.data.photos.push({ id: params.id, owner_id: String(user.id), type, size: data.length, created_at: new Date().toISOString() });
      }
      store.save();
      return { status: existing ? 200 : 201, body: { id: params.id, url: `/photos/${params.id}`, size: data.length } };
    },
    { auth: true, raw: true },
  );

  router.add(
    'GET',
    '/photos/:id',
    ({ params, user }) => {
      const photo = store.data.photos.find((p) => p.id === params.id);
      const url = `/photos/${params.id}`;
      const allowed = photo && (photo.owner_id === String(user.id) ||
        store.data.posts.some((p) => (p.photo_url === url || (p.gallery || []).includes(url)) && canSee(p, user)));
      if (!allowed) throw new ApiError(404, 'Poza nu există.');
      let data;
      try {
        data = fs.readFileSync(path.join(photosDir, photo.id));
      } catch {
        throw new ApiError(404, 'Poza nu există.');
      }
      return { status: 200, binary: data, contentType: photo.type, headers: { 'Cache-Control': 'private, max-age=86400' } };
    },
    { auth: true },
  );

  // ---------------------------------------------------------------------------
  // Aprecieri si comentarii (doar conturile USER cu cont propriu; adminul poate sterge comentarii)
  // ---------------------------------------------------------------------------

  function requireMember(user) {
    if (user.role !== 'USER' || user.provider === PROVIDERS.ANONYMOUS) {
      throw new ApiError(403, user.role === 'ADMIN'
        ? 'Administratorii nu apreciază și nu comentează postări.'
        : 'Creați un cont pentru a aprecia sau comenta postări.');
    }
  }

  function findPublicPostOr404(id, user) {
    const post = findVisiblePostOr404(id, user);
    if (post.status === 'DRAFT') throw new ApiError(409, 'O ciornă nu poate fi apreciată sau comentată.');
    return post;
  }

  router.add(
    'PUT',
    '/post/:id/like',
    ({ params, user }) => {
      requireMember(user);
      const post = findPublicPostOr404(params.id, user);
      if (!store.data.likes.some((l) => l.post_id === post.id && l.user_id === String(user.id))) {
        store.data.likes.push({ post_id: post.id, user_id: String(user.id), created_at: new Date().toISOString() });
        store.save();
        broadcastPost('updated', post);
      }
      return { status: 200, body: forViewer(post, user) };
    },
    { auth: true },
  );

  router.add(
    'DELETE',
    '/post/:id/like',
    ({ params, user }) => {
      requireMember(user);
      const post = findVisiblePostOr404(params.id, user);
      const before = store.data.likes.length;
      store.data.likes = store.data.likes.filter((l) => !(l.post_id === post.id && l.user_id === String(user.id)));
      if (store.data.likes.length !== before) {
        store.save();
        broadcastPost('updated', post);
      }
      return { status: 200, body: forViewer(post, user) };
    },
    { auth: true },
  );

  /**
   * Comentariile, ca intr-o retea sociala: raspunsuri (un nivel), aprecieri, editare ("editat"),
   * stergere de catre autor / autorul postarii / admin. Orice schimbare trimite prin WebSocket
   * evenimentul "comments" celor care pot vedea postarea, ca ecranul deschis sa se actualizeze.
   */
  function publicComment(comment, viewer, post) {
    const likes = store.data.commentLikes.filter((l) => l.comment_id === comment.id);
    const mine = Boolean(viewer) && comment.user_id === String(viewer.id);
    return {
      ...comment,
      parent_id: comment.parent_id || '',
      edited_at: comment.edited_at || '',
      like_count: likes.length,
      liked_by_me: Boolean(viewer) && likes.some((l) => l.user_id === String(viewer.id)),
      can_edit: mine,
      can_delete: mine || (Boolean(viewer) && (post.user_id === String(viewer.id) || viewer.role === 'ADMIN')),
    };
  }

  function broadcastComments(post) {
    hub.broadcastWith((viewer) => (canSee(post, viewer) ? { event: 'comments', payload: { item: { id: post.id } } } : null));
  }

  function commentText(body) {
    const text = typeof body.text === 'string' ? body.text.trim() : '';
    if (!text) throw new ApiError(400, 'Comentariul nu poate fi gol.');
    if (text.length > 500) throw new ApiError(400, 'Comentariul poate avea cel mult 500 de caractere.');
    return text;
  }

  function findCommentOr404(id, user) {
    const comment = store.data.comments.find((c) => c.id === id);
    const post = comment && store.findPost(comment.post_id);
    if (!comment || !post || !canSee(post, user)) throw new ApiError(404, 'Comentariul nu mai există.');
    return { comment, post };
  }

  router.add(
    'GET',
    '/post/:id/comments',
    ({ params, user }) => {
      const post = findVisiblePostOr404(params.id, user);
      const comments = store.data.comments
        .filter((c) => c.post_id === post.id)
        .sort((a, b) => a.created_at.localeCompare(b.created_at))
        .map((c) => publicComment(c, user, post));
      return { status: 200, body: comments };
    },
    { auth: true },
  );

  router.add(
    'POST',
    '/post/:id/comments',
    ({ params, body, user }) => {
      requireMember(user);
      const post = findPublicPostOr404(params.id, user);
      const text = commentText(body);
      // raspuns la un comentariu: mereu la comentariul de pe primul nivel (fire de discutie simple)
      let parentId = '';
      if (typeof body.parent_id === 'string' && body.parent_id) {
        const parent = store.data.comments.find((c) => c.id === body.parent_id && c.post_id === post.id);
        if (!parent) throw new ApiError(404, 'Comentariul la care răspundeți nu mai există.');
        parentId = parent.parent_id || parent.id;
      }
      const comment = {
        id: crypto.randomUUID(),
        post_id: post.id,
        parent_id: parentId,
        user_id: String(user.id),
        author: user.email.split('@')[0],
        text,
        created_at: new Date().toISOString(),
      };
      store.data.comments.push(comment);
      store.save();
      broadcastPost('updated', post);
      broadcastComments(post);
      return { status: 201, body: publicComment(comment, user, post) };
    },
    { auth: true },
  );

  router.add(
    'PATCH',
    '/comments/:id',
    ({ params, body, user }) => {
      const { comment, post } = findCommentOr404(params.id, user);
      if (comment.user_id !== String(user.id)) throw new ApiError(403, 'Puteți edita doar comentariile proprii.');
      comment.text = commentText(body);
      comment.edited_at = new Date().toISOString();
      store.save();
      broadcastComments(post);
      return { status: 200, body: publicComment(comment, user, post) };
    },
    { auth: true },
  );

  router.add(
    'DELETE',
    '/comments/:id',
    ({ params, user }) => {
      const { comment, post } = findCommentOr404(params.id, user);
      if (!publicComment(comment, user, post).can_delete) {
        throw new ApiError(403, 'Puteți șterge doar comentariile proprii sau pe cele de la postările voastre.');
      }
      // un comentariu sters ia cu el si raspunsurile lui
      const removed = new Set(store.data.comments.filter((c) => c.id === comment.id || c.parent_id === comment.id).map((c) => c.id));
      store.data.comments = store.data.comments.filter((c) => !removed.has(c.id));
      store.data.commentLikes = store.data.commentLikes.filter((l) => !removed.has(l.comment_id));
      store.save();
      broadcastPost('updated', post);
      broadcastComments(post);
      return { status: 204 };
    },
    { auth: true },
  );

  for (const method of ['PUT', 'DELETE']) {
    router.add(
      method,
      '/comments/:id/like',
      ({ params, user }) => {
        requireMember(user);
        const { comment, post } = findCommentOr404(params.id, user);
        const has = store.data.commentLikes.some((l) => l.comment_id === comment.id && l.user_id === String(user.id));
        if (method === 'PUT' && !has) store.data.commentLikes.push({ comment_id: comment.id, user_id: String(user.id) });
        if (method === 'DELETE' && has) {
          store.data.commentLikes = store.data.commentLikes.filter((l) => !(l.comment_id === comment.id && l.user_id === String(user.id)));
        }
        if ((method === 'PUT') !== has) {
          store.save();
          broadcastComments(post);
        }
        return { status: 200, body: publicComment(comment, user, post) };
      },
      { auth: true },
    );
  }

  // ---------------------------------------------------------------------------
  // Calatorii (albume): grupeaza postarile unui utilizator. Create si offline pe telefon
  // (id = UUID de pe telefon), trimise cu PUT (upsert), sterse cu DELETE.
  // ---------------------------------------------------------------------------

  function validTrip(body) {
    const title = typeof body.title === 'string' ? body.title.trim() : '';
    if (!title) throw new ApiError(400, 'Călătoria trebuie să aibă un nume.');
    if (title.length > 80) throw new ApiError(400, 'Numele călătoriei poate avea cel mult 80 de caractere.');
    const description = typeof body.description === 'string' ? body.description.trim() : '';
    if (description.length > 1000) throw new ApiError(400, 'Descrierea poate avea cel mult 1000 de caractere.');
    const start = Number(body.start_date) || 0;
    const end = Number(body.end_date) || 0;
    if (start < 0 || end < 0 || (start && end && end < start)) throw new ApiError(400, 'Data de sfârșit nu poate fi înaintea celei de început.');
    return { title, description, start_date: start, end_date: end };
  }

  /** Calatoria + cate postari publice are si cate locuri, pentru cine o priveste. */
  function publicTrip(trip, viewer) {
    const posts = store.data.posts.filter((p) => p.trip_id === trip.id && canSee(p, viewer));
    return {
      id: trip.id,
      user_id: trip.owner_id,
      title: trip.title,
      description: trip.description,
      start_date: trip.start_date,
      end_date: trip.end_date,
      created_at: trip.created_at,
      updated_at: trip.updated_at,
      post_count: posts.length,
      cover: (posts.find((p) => p.photo_url) || {}).photo_url || '',
    };
  }

  router.add(
    'PUT',
    '/trips/:id',
    ({ params, body, user }) => {
      requirePublisher(user);
      if (!ID_PATTERN.test(params.id)) throw new ApiError(400, 'Identificator de călătorie invalid.');
      const existing = store.data.trips.find((t) => t.id === params.id);
      if (existing && existing.owner_id !== String(user.id)) throw new ApiError(404, 'Călătoria nu există.');
      if (existing && existing.deleted) throw new ApiError(410, 'Călătoria a fost ștearsă.');
      const input = validTrip(body);
      const now = new Date().toISOString();
      if (existing) {
        Object.assign(existing, input, { updated_at: now });
      } else {
        store.data.trips.push({ id: params.id, owner_id: String(user.id), ...input, created_at: now, updated_at: now });
      }
      store.save();
      const trip = store.data.trips.find((t) => t.id === params.id);
      return { status: existing ? 200 : 201, body: publicTrip(trip, user) };
    },
    { auth: true },
  );

  router.add(
    'DELETE',
    '/trips/:id',
    ({ params, user }) => {
      const trip = store.data.trips.find((t) => t.id === params.id);
      if (!trip || trip.deleted) return { status: 204 };
      if (trip.owner_id !== String(user.id)) throw new ApiError(404, 'Călătoria nu există.');
      trip.deleted = true;
      // postarile raman, doar nu mai apartin calatoriei
      for (const post of store.data.posts.filter((p) => p.trip_id === trip.id)) {
        const updated = { ...post, trip_id: '' };
        store.replacePost(updated);
        broadcastPost('updated', updated);
      }
      store.save();
      return { status: 204 };
    },
    { auth: true },
  );

  // ---------------------------------------------------------------------------
  // Profilul altor calatori si urmarirea lor
  // ---------------------------------------------------------------------------

  function publicUserOr404(id) {
    const user = store.findUserById(Number(id)) || store.findUserById(id);
    if (!user || user.role === 'ADMIN') throw new ApiError(404, 'Utilizatorul nu există.');
    return user;
  }

  /** Profilul public: doar numele afisat (nu adresa de e-mail) si statistici din postarile vizibile. */
  function publicProfile(person, viewer) {
    const id = String(person.id);
    const posts = store.data.posts.filter((p) => p.user_id === id && p.status !== 'DRAFT' && canSee(p, viewer));
    const rated = posts.filter((p) => p.rating > 0);
    return {
      id,
      name: person.provider === PROVIDERS.ANONYMOUS ? 'Vizitator' : person.email.split('@')[0],
      provider: person.provider,
      created_at: person.createdAt,
      post_count: posts.length,
      place_count: new Set(posts.map((p) => (p.place || '').trim().toLowerCase()).filter(Boolean)).size,
      average_rating: rated.length ? rated.reduce((sum, p) => sum + p.rating, 0) / rated.length : 0,
      likes_received: store.data.likes.filter((l) => posts.some((p) => p.id === l.post_id)).length,
      trip_count: store.data.trips.filter((t) => t.owner_id === id && !t.deleted).length,
      followers: store.data.follows.filter((f) => f.followee_id === id).length,
      following: store.data.follows.filter((f) => f.follower_id === id).length,
      followed_by_me: store.data.follows.some((f) => f.follower_id === String(viewer.id) && f.followee_id === id),
      is_me: id === String(viewer.id),
    };
  }

  router.add('GET', '/users/:id/profile', ({ params, user }) => ({
    status: 200,
    body: publicProfile(publicUserOr404(params.id), user),
  }), { auth: true });

  router.add('GET', '/users/:id/trips', ({ params, user }) => {
    const person = publicUserOr404(params.id);
    const trips = store.data.trips
      .filter((t) => t.owner_id === String(person.id) && !t.deleted)
      .map((t) => publicTrip(t, user))
      // ale altora: doar calatoriile cu postari publice
      .filter((t) => String(person.id) === String(user.id) || t.post_count > 0)
      .sort((a, b) => (b.start_date || 0) - (a.start_date || 0));
    return { status: 200, body: trips };
  }, { auth: true });

  router.add('GET', '/me/following', ({ user }) => ({
    status: 200,
    body: store.data.follows.filter((f) => f.follower_id === String(user.id)).map((f) => f.followee_id),
  }), { auth: true });

  for (const method of ['PUT', 'DELETE']) {
    router.add(
      method,
      '/users/:id/follow',
      ({ params, user }) => {
        requireMember(user);
        const person = publicUserOr404(params.id);
        const id = String(person.id);
        if (id === String(user.id)) throw new ApiError(400, 'Nu vă puteți urmări propriul profil.');
        const has = store.data.follows.some((f) => f.follower_id === String(user.id) && f.followee_id === id);
        if (method === 'PUT' && !has) store.data.follows.push({ follower_id: String(user.id), followee_id: id, created_at: new Date().toISOString() });
        if (method === 'DELETE' && has) {
          store.data.follows = store.data.follows.filter((f) => !(f.follower_id === String(user.id) && f.followee_id === id));
        }
        store.save();
        return { status: 200, body: publicProfile(person, user) };
      },
      { auth: true },
    );
  }

  // ---------------------------------------------------------------------------
  // Profil: schimbarea parolei (conturile cu e-mail si parola)
  // ---------------------------------------------------------------------------

  router.add(
    'POST',
    '/me/password',
    ({ body, user, claims }) => {
      if (user.provider !== PROVIDERS.EMAIL) {
        throw new ApiError(400, 'Contul dumneavoastră nu folosește o parolă (Google, Apple sau vizitator).');
      }
      const current = typeof body.currentPassword === 'string' ? body.currentPassword : '';
      const next = typeof body.newPassword === 'string' ? body.newPassword : '';
      if (!security.verifyPassword(current, user.passwordHash || security.DUMMY_HASH)) {
        throw new ApiError(400, 'Parola actuală nu este corectă.');
      }
      if (current === next) throw new ApiError(400, 'Parola nouă trebuie să fie diferită de cea actuală.');
      const problems = security.passwordProblems(next, user.email);
      if (problems.length) throw new ApiError(400, `Parola nouă nu este suficient de sigură: ${problems.join(', ')}.`, { problems });
      store.updateUser(user.id, { passwordHash: security.hashPassword(next) });
      // celelalte sesiuni raman valabile pana expira; cea curenta continua
      return { status: 200, body: { ...publicUser(store.findUserById(user.id)), changed: true } };
    },
    { auth: true },
  );

  // ---------------------------------------------------------------------------
  // Moderare (doar ADMIN): ascunde / reafiseaza o postare, statistici
  // ---------------------------------------------------------------------------

  router.add(
    'PATCH',
    '/post/:id/visibility',
    ({ params, body, user }) => {
      requireAdmin(user);
      if (typeof body.hidden !== 'boolean') throw new ApiError(400, 'Câmpul „hidden” trebuie să fie true sau false.');
      const existing = store.findPost(params.id);
      if (!existing || existing.status === 'DRAFT') throw new ApiError(404, 'Postarea nu mai există.');
      const updated = { ...existing, hidden: body.hidden };
      store.replacePost(updated);
      broadcastPost('updated', updated);
      return { status: 200, body: forViewer(updated, user) };
    },
    { auth: true },
  );

  router.add(
    'GET',
    '/stats',
    ({ user }) => {
      requireAdmin(user);
      const users = store.listUsers();
      const posts = store.listPosts();
      return {
        status: 200,
        body: {
          users: {
            total: users.length,
            active: users.filter((u) => !u.blocked).length,
            blocked: users.filter((u) => u.blocked).length,
            admins: users.filter((u) => u.role === 'ADMIN').length,
          },
          posts: {
            total: posts.length,
            active: posts.filter((p) => p.status !== 'DRAFT' && !p.hidden).length,
            drafts: posts.filter((p) => p.status === 'DRAFT').length,
            hidden: posts.filter((p) => p.hidden).length,
            deletedByModerators: store.countTombstones({ byModerator: true }),
          },
        },
      };
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

  // Trackerele WanderLog (IoT): inregistrare cu cheie per dispozitiv, revocare, actualizari semnate
  registerDeviceRoutes(router, store, settings);

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

      const body = !route.raw && ['POST', 'PUT', 'PATCH'].includes(req.method)
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
      if (result.binary) {
        res.writeHead(result.status, {
          'Content-Type': result.contentType || 'application/octet-stream',
          'Content-Length': result.binary.length,
          'X-Content-Type-Options': 'nosniff',
          ...(result.headers || {}),
        });
        res.end(result.binary);
      } else {
        sendJson(res, result.status, result.body);
      }
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
