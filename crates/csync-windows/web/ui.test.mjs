import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {createTranslator} from './i18n.mjs';
import {materialIcon} from './material-icons.mjs';

test('desktop navigation, safe device names, and deletion by identity', () => {
  class Element {
    children = []; hidden = false; open = false; value = ''; attributes = {};
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(key, value) { this.attributes[key] = value; }
    getAttribute(key) { return this.attributes[key]; }
    removeAttribute(key) { delete this.attributes[key]; }
    addEventListener() {}
    show() { this.open = true; }
    close() { this.open = false; }
  }
  const nodes = new Map();
  const get = id => { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); };
  const sent = [];
  const catalog = Object.fromEntries(['zh-Hans', 'en-US', 'qps-keys'].map(locale => [locale,
    JSON.parse(readFileSync(new URL(`../../../locales/${locale}.json`, import.meta.url), 'utf8'))]));
  const window = {csyncCatalog: catalog, ipc: {postMessage: value => sent.push(JSON.parse(value))}, scrollTo() {}};
  const document = {getElementById: get, createElement: () => new Element(), createElementNS: () => new Element(), querySelector: () => null, querySelectorAll: () => [], documentElement: {}};
  const source = readFileSync(new URL('app.js', import.meta.url), 'utf8').replace(/^import .*;\r?\n/gm, '');
  vm.runInNewContext(source, {window, document, createTranslator, materialIcon: name => materialIcon(name, document)});
  const peers = [{id: 'first', name: '<img onerror=alert(1)>', online: true}, {id: 'second', name: '<img onerror=alert(1)>', online: false}];
  const state = {peers, locale: 'en-US', language: 'en-US', addresses: [], port: 45876, runningPort: 45876, pairingCompleted: 0};
  window.updateState(state);
  get('nav-devices').onclick();
  assert.equal(get('device-page').hidden, false);
  assert.equal(get('sync').hidden, true);
  assert.equal(get('nav-devices').attributes['aria-current'], 'page');
  assert.equal(get('nav-devices').attributes['data-active'], 'true');
  assert.equal(get('nav-sync').attributes['data-active'], 'false');
  const rows = get('devices').children;
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children[1].children[0].textContent, peers[0].name);
  assert.equal(rows[1].children[1].children[1].textContent, 'Not connected');
  rows[1].children[2].onclick();
  assert.equal(get('delete-dialog').open, true);
  get('confirm-delete').onclick();
  assert.deepEqual(sent.at(-1), {action: 'deletePeer', id: 'second'});
  get('add-device').onclick();
  assert.equal(get('pair-panel').hidden, false);
  assert.deepEqual(sent.at(-1), {action: 'pair'});
  window.updateState({...state, pairingOpen: true, pairingQr: 'data:image/svg+xml;base64,test'});
  assert.equal(get('pair-qr-panel').hidden, false);
  assert.equal(get('pair-qr').getAttribute('src'), 'data:image/svg+xml;base64,test');
  window.updateState({...state, pairingOpen: false});
  assert.equal(get('pair-qr-panel').hidden, true);
  assert.equal(get('pair-qr').getAttribute('src'), undefined);
  assert.equal(get('pair-panel').hidden, false, 'closed pairing window alone is not successful enrollment');
  window.updateState({...state, pairingCompleted: 1, pairingOpen: false});
  assert.equal(get('pair-panel').hidden, true, 'success hides the panel even when the peer list is unchanged');
  get('add-device').onclick();
  assert.equal(get('pair-panel').hidden, false);
  assert.deepEqual(sent.at(-1), {action: 'pair'});
  window.updateState({...state, pairingCompleted: 1, pairingOpen: true, pairingQr: 'data:image/svg+xml;base64,next'});
  assert.equal(get('pair-panel').hidden, false);
  assert.equal(get('pair-qr-panel').hidden, false);
  rows[0].children[2].onclick();
  window.updateState({...state, peers: []});
  assert.equal(get('devices-empty').hidden, false);
  assert.equal(get('delete-dialog').open, false);
  assert.equal(get('confirm-delete').disabled, true);
  get('nav-settings').onclick();
  assert.equal(get('settings').hidden, false);
  assert.equal(get('device-page').hidden, true);
});
