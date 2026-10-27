'use strict';
// Runs in every page and frame before the page's own scripts. It asks the browser for the
// protection script (fingerprint, WebRTC, location) and runs it in the page's own world.
// Pages get no access to anything else.
const { ipcRenderer, webFrame } = require('electron');

try {
  const code = ipcRenderer.sendSync('page-script');
  if (code) webFrame.executeJavaScript(code);
} catch (e) {
  // A page that cannot be protected still loads; the network route is protected regardless.
}
