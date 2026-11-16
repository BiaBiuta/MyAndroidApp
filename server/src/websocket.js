'use strict';

const crypto = require('node:crypto');

/**
 * Server WebSocket minimal (RFC 6455), fara biblioteci externe.
 *
 * Protocolul cu aplicatia:
 *  - clientul se conecteaza si trimite {"type":"authorization","payload":{"token":"<JWT>"}};
 *  - serverul trimite doar clientilor autorizati evenimentele
 *    {"event":"created|updated|deleted","payload":{"item":{...postare}}}.
 * Alte mesaje de la client sunt ignorate: doar serverul anunta modificarile.
 */

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const MAX_MESSAGE_BYTES = 12 * 1024 * 1024; // aceeasi limita ca pentru cererile HTTP (poze Base64)
const OPCODE = { continuation: 0x0, text: 0x1, binary: 0x2, close: 0x8, ping: 0x9, pong: 0xa };

class WsConnection {
  constructor(socket, onText) {
    this.socket = socket;
    this.onText = onText;
    this.buffer = Buffer.alloc(0);
    this.fragments = [];
    this.closed = false;
    this.user = null; // completat dupa mesajul de autorizare
    this.alive = true;

    socket.on('data', (chunk) => this.receive(chunk));
    socket.on('close', () => (this.closed = true));
    socket.on('error', () => (this.closed = true));
  }

  receive(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 2) {
      const first = this.buffer[0];
      const second = this.buffer[1];
      const fin = (first & 0x80) !== 0;
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;

      if (length === 126) {
        if (this.buffer.length < 4) return;
        length = this.buffer.readUInt16BE(2);
        offset = 4;
      } else if (length === 127) {
        if (this.buffer.length < 10) return;
        length = Number(this.buffer.readBigUInt64BE(2));
        offset = 10;
      }
      if (length > MAX_MESSAGE_BYTES) return this.close(1009, 'Mesaj prea mare');
      // conform RFC 6455, tot ce trimite clientul trebuie sa fie mascat
      if (!masked) return this.close(1002, 'Cadru nemascat');

      if (this.buffer.length < offset + 4 + length) return; // cadru incomplet, asteptam
      const mask = this.buffer.subarray(offset, offset + 4);
      const payload = Buffer.from(this.buffer.subarray(offset + 4, offset + 4 + length));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      this.buffer = this.buffer.subarray(offset + 4 + length);

      this.handleFrame(fin, opcode, payload);
      if (this.closed) return;
    }
  }

  handleFrame(fin, opcode, payload) {
    switch (opcode) {
      case OPCODE.text:
      case OPCODE.continuation:
        this.fragments.push(payload);
        if (fin) {
          const text = Buffer.concat(this.fragments).toString('utf8');
          this.fragments = [];
          this.onText(this, text);
        }
        break;
      case OPCODE.ping:
        this.write(OPCODE.pong, payload);
        break;
      case OPCODE.pong:
        this.alive = true;
        break;
      case OPCODE.close:
        this.close(1000, '');
        break;
      default:
        break; // cadrele binare nu sunt folosite
    }
  }

  write(opcode, payload) {
    if (this.closed) return;
    const length = payload.length;
    let header;
    if (length < 126) {
      header = Buffer.from([0x80 | opcode, length]);
    } else if (length < 65536) {
      header = Buffer.alloc(4);
      header[0] = 0x80 | opcode;
      header[1] = 126;
      header.writeUInt16BE(length, 2);
    } else {
      header = Buffer.alloc(10);
      header[0] = 0x80 | opcode;
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(length), 2);
    }
    this.socket.write(Buffer.concat([header, payload]));
  }

  sendJson(value) {
    this.write(OPCODE.text, Buffer.from(JSON.stringify(value), 'utf8'));
  }

  close(code = 1000, reason = '') {
    if (this.closed) return;
    const payload = Buffer.alloc(2 + Buffer.byteLength(reason));
    payload.writeUInt16BE(code, 0);
    payload.write(reason, 2);
    this.write(OPCODE.close, payload);
    this.closed = true;
    this.socket.end();
  }
}

class WsHub {
  /**
   * @param authenticate functie (token) -> utilizator sau null
   */
  constructor(authenticate) {
    this.authenticate = authenticate;
    this.connections = new Set();
    // ping la 30 s: conexiunile moarte (telefon fara retea) se inchid
    this.heartbeat = setInterval(() => this.ping(), 30_000);
    this.heartbeat.unref();
  }

  /** Atasat la evenimentul 'upgrade' al serverului HTTP. */
  handleUpgrade(req, socket, head) {
    const key = req.headers['sec-websocket-key'];
    if ((req.headers.upgrade || '').toLowerCase() !== 'websocket' || !key) {
      socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
      return;
    }
    const accept = crypto.createHash('sha1').update(key + GUID).digest('base64');
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    );
    socket.setNoDelay(true);
    const connection = new WsConnection(socket, (conn, text) => this.onMessage(conn, text));
    this.connections.add(connection);
    socket.on('close', () => this.connections.delete(connection));
    // octetii primiti deja odata cu cererea de upgrade (daca exista)
    if (head && head.length) connection.receive(head);
  }

  onMessage(connection, text) {
    let message;
    try {
      message = JSON.parse(text);
    } catch {
      return;
    }
    if (message && message.type === 'authorization') {
      const user = this.authenticate(message.payload && message.payload.token);
      if (!user) {
        connection.close(4001, 'Token invalid');
        return;
      }
      connection.user = user;
    }
  }

  /**
   * Trimite fiecarui client autorizat mesajul calculat pentru el ([messageFor](utilizator)
   * intoarce mesajul sau null): ciornele si postarile ascunse nu ajung la cine nu le poate vedea.
   */
  broadcastWith(messageFor) {
    for (const connection of this.connections) {
      if (!connection.user || connection.closed) continue;
      const message = messageFor(connection.user);
      if (message) connection.sendJson(message);
    }
  }

  /** Trimite evenimentul tuturor clientilor autorizati. */
  broadcast(event, item) {
    for (const connection of this.connections) {
      if (connection.user && !connection.closed) {
        connection.sendJson({ event, payload: { item } });
      }
    }
  }

  ping() {
    for (const connection of this.connections) {
      if (!connection.alive) {
        connection.close(1001, 'Fara raspuns');
        this.connections.delete(connection);
        continue;
      }
      connection.alive = false;
      connection.write(OPCODE.ping, Buffer.alloc(0));
    }
  }

  closeAll() {
    clearInterval(this.heartbeat);
    for (const connection of this.connections) connection.close(1001, 'Serverul se opreste');
    this.connections.clear();
  }
}

module.exports = { WsHub };
