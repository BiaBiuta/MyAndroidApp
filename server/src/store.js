'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

/**
 * Baza de date a serverului: un fisier JSON (data/db.json), tinut in memorie si scris
 * pe disc dupa fiecare modificare. Scrierea e atomica (fisier temporar + rename), deci
 * o oprire brusca a serverului nu lasa fisierul pe jumatate scris.
 */
class Store {
  constructor(dataDir) {
    this.dataDir = dataDir;
    this.file = path.join(dataDir, 'db.json');
    fs.mkdirSync(dataDir, { recursive: true });
    this.data = this.load();
    this.secret = this.loadSecret();
  }

  load() {
    const empty = { nextUserId: 1, users: [], posts: [], revokedTokens: [] };
    try {
      return { ...empty, ...JSON.parse(fs.readFileSync(this.file, 'utf8')) };
    } catch (error) {
      if (error.code !== 'ENOENT') console.warn('db.json nu a putut fi citit, pornim cu o baza goala', error);
      return empty;
    }
  }

  /** Secretul cu care se semneaza token-urile: generat o data, pastrat langa baza de date. */
  loadSecret() {
    if (process.env.JWT_SECRET) return process.env.JWT_SECRET;
    const file = path.join(this.dataDir, 'jwt-secret.key');
    try {
      return fs.readFileSync(file, 'utf8').trim();
    } catch {
      const secret = crypto.randomBytes(48).toString('base64url');
      fs.writeFileSync(file, secret, { mode: 0o600 });
      return secret;
    }
  }

  save() {
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data));
    fs.renameSync(tmp, this.file);
  }

  // ---------- Utilizatori ----------

  findUserByEmail(email) {
    return this.data.users.find((u) => u.email === email) || null;
  }

  findUserById(id) {
    return this.data.users.find((u) => u.id === id) || null;
  }

  createUser({ email, passwordHash = '', provider, role }) {
    const user = {
      id: this.data.nextUserId++,
      email,
      passwordHash,
      provider,
      role,
      blocked: false,
      createdAt: new Date().toISOString(),
    };
    this.data.users.push(user);
    this.save();
    return user;
  }

  updateUser(id, changes) {
    const user = this.findUserById(id);
    if (!user) return null;
    Object.assign(user, changes);
    this.save();
    return user;
  }

  listUsers() {
    return this.data.users;
  }

  countActiveAdmins() {
    return this.data.users.filter((u) => u.role === 'ADMIN' && !u.blocked).length;
  }

  // ---------- Token-uri revocate (Logout) ----------

  revokeToken(jti, exp) {
    const now = Math.floor(Date.now() / 1000);
    // token-urile expirate nu mai trebuie tinute minte
    this.data.revokedTokens = this.data.revokedTokens.filter((t) => t.exp > now);
    this.data.revokedTokens.push({ jti, exp });
    this.save();
  }

  isRevoked(jti) {
    return this.data.revokedTokens.some((t) => t.jti === jti);
  }

  // ---------- Postari ----------

  listPosts() {
    return [...this.data.posts].sort((a, b) => b.created_at.localeCompare(a.created_at));
  }

  findPost(id) {
    return this.data.posts.find((p) => p.id === id) || null;
  }

  insertPost(post) {
    this.data.posts.push(post);
    this.save();
    return post;
  }

  replacePost(post) {
    const index = this.data.posts.findIndex((p) => p.id === post.id);
    if (index === -1) return null;
    this.data.posts[index] = post;
    this.save();
    return post;
  }

  deletePost(id) {
    const before = this.data.posts.length;
    this.data.posts = this.data.posts.filter((p) => p.id !== id);
    if (this.data.posts.length !== before) this.save();
    return this.data.posts.length !== before;
  }
}

module.exports = { Store };
