'use strict';

const os = require('node:os');
const config = require('./src/config');
const { createApp } = require('./src/app');

const { server, close } = createApp();

server.listen(config.port, config.host, () => {
  console.log(`Serverul WanderLog ruleaza pe portul ${config.port}`);
  console.log(`  Emulator Android:       http://10.0.2.2:${config.port}`);
  // adresele din reteaua locala, pentru un telefon real conectat la acelasi Wi-Fi
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const a of addresses || []) {
      if (a.family === 'IPv4' && !a.internal) console.log(`  Telefon (acelasi Wi-Fi): http://${a.address}:${config.port}`);
    }
  }
});

// Ctrl+C: inchidem conexiunile WebSocket si serverul HTTP curat
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log('\nServerul se opreste...');
    close().then(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  });
}
