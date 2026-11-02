import '@material/web/button/filled-button.js';
import '@material/web/button/filled-tonal-button.js';
import '@material/web/button/outlined-button.js';
import '@material/web/button/text-button.js';
import '@material/web/switch/switch.js';
import '@material/web/textfield/outlined-text-field.js';
import '@material/web/dialog/dialog.js';
import '@material/web/select/outlined-select.js';
import '@material/web/select/select-option.js';
import {createTranslator} from './i18n.mjs';
import {materialIcon} from './material-icons.mjs';
for (const slot of document.querySelectorAll('[data-material-icon]')) slot.replaceChildren(materialIcon(slot.dataset.materialIcon));
const $ = id => document.getElementById(id);
const send = (action, values = {}) => window.ipc.postMessage(JSON.stringify({action,...values}));
let initialized = false, pairCode = null, deletingPeer = null, peersSignature = '';
let currentPeers = [];
let pairingCompleted;
const catalog = window.csyncCatalog;
let {t} = createTranslator(catalog, 'zh-Hans');
function localize(locale) {
 ({t} = createTranslator(catalog, locale));
 document.documentElement.lang = locale;
 document.documentElement.dir = catalog[locale]?._meta.direction || 'ltr';
 for (const element of document.querySelectorAll('[data-i18n]')) element.textContent = t(element.dataset.i18n);
 for (const attribute of ['label', 'supporting-text', 'aria-label', 'alt']) {
   for (const element of document.querySelectorAll(`[data-i18n-${attribute}]`)) element.setAttribute(attribute, t(element.getAttribute(`data-i18n-${attribute}`)));
 }
 if ($('port').error) $('port').errorText = t('windows.invalid_port');
}
let renderedLocale;
const languageOptions = [['system', null], ...Object.entries(catalog).map(([locale, value]) => [locale, value._meta.nativeName])];
// Keep option identities stable across locale changes. Material Web caches the
// selected option, and replacing it can clear the field after slot updates.
const languageElements = languageOptions.map(([locale]) => {
 const option = document.createElement('md-select-option');
 option.value = locale;
 const label = document.createElement('div');
 label.slot = 'headline';
 option.append(label);
 return {option, label};
});
$('language').replaceChildren(...languageElements.map(({option}) => option));
function renderLanguages() {
 languageElements.forEach(({option, label}, index) => {
   const text = languageOptions[index][1] || t('language.system');
   label.textContent = text;
   // Supply text explicitly so selection does not depend on async slot rendering.
   option.displayText = text;
 });
}
$('language').addEventListener('change', () => send('language', {language: $('language').value}));
const showPage = page => {
  for (const other of ['sync','devices','settings','about']) $(other === 'devices' ? 'device-page' : other).hidden = other !== page;
  for (const other of ['sync','devices','settings']) {
    const active = other === page || (page === 'about' && other === 'settings');
    $('nav-'+other).setAttribute('aria-current', active ? 'page' : 'false');
    // Material Web delegates ARIA attributes into its shadow root; keep visual state separate.
    $('nav-'+other).setAttribute('data-active', String(active));
  }
  window.scrollTo(0, 0);
  const heading = document.querySelector(`#${page === 'devices' ? 'device-page' : page} h1`);
  if (heading) { heading.tabIndex = -1; heading.focus({preventScroll: true}); }
};
for (const page of ['sync','devices','settings']) $('nav-'+page).onclick = () => showPage(page);
$('manage-devices').onclick = () => showPage('devices');
$('add-device').onclick = () => { $('pair-panel').hidden = false; send('pair'); };
$('open-about').onclick = () => showPage('about');
$('back-about').onclick = () => showPage('settings');
$('pause').onclick = () => send('pause');
$('pair').onclick = () => send('pair');
$('close-pair').onclick = () => send('closePair');
$('save').onclick = () => {
 const port = Number($('port').value);
 if (!Number.isInteger(port) || port < 1 || port > 65535) { $('port').error = true; $('port').errorText = t('windows.invalid_port'); return; }
 $('port').error = false;
 send('save', {port,...Object.fromEntries(['public_access','auto_start'].map(key => [key,$(key).selected]))});
};
function updateDeleteDialog() {
 const peer = currentPeers.find(peer => peer.id === deletingPeer);
 $('confirm-delete').disabled = !peer;
 $('delete-device-hint').textContent = peer ? t('windows.delete_device_hint', {device: peer.name}) : '';
 if (!peer && $('delete-dialog').open) $('delete-dialog').close();
}
$('cancel-delete').onclick = () => $('delete-dialog').close();
$('confirm-delete').onclick = () => {
 if (currentPeers.some(peer => peer.id === deletingPeer)) send('deletePeer', {id: deletingPeer});
 $('delete-dialog').close();
};
function renderPeers(peers, locale) {
 currentPeers = peers;
 updateDeleteDialog();
 const signature = JSON.stringify([peers, locale]);
 if (signature === peersSignature) return;
 peersSignature = signature;
 $('devices-empty').hidden = peers.length > 0;
 $('devices').replaceChildren(...peers.map(peer => {
   const row = document.createElement('li'); row.className = 'device-row';
   const avatar = document.createElement('div'); avatar.className = 'device-avatar'; avatar.append(materialIcon('mobile')); avatar.setAttribute('aria-hidden', 'true');
   const copy = document.createElement('div'); copy.className = 'device-copy';
   const name = document.createElement('div'); name.className = 'device-name'; name.textContent = peer.name;
   const status = document.createElement('div'); status.className = 'device-status'; status.textContent = t(peer.online ? 'windows.device_online' : 'windows.device_offline');
   copy.append(name, status);
   const remove = document.createElement('md-outlined-button'); remove.className = 'delete-device'; remove.textContent = t('common.delete');
   const deleteIcon = materialIcon('delete'); deleteIcon.setAttribute('slot', 'icon'); remove.append(deleteIcon);
   remove.setAttribute('aria-label', t('hosts.delete_accessibility', {device: peer.name}));
   remove.onclick = () => { deletingPeer = peer.id; updateDeleteDialog(); $('delete-dialog').show(); };
   row.append(avatar, copy, remove); return row;
 }));
}
for(const [id,approved] of [['approve',true],['reject',false]]) $(id).onclick = () => { send('confirmPair',{approved}); $('pair-dialog').close(); };
$('pair-dialog').addEventListener('cancel', () => send('confirmPair',{approved:false}));
window.updateState = state => {
 if (renderedLocale !== state.locale) { localize(state.locale); renderLanguages(); renderedLocale = state.locale; }
 $('language').value = state.language;
 $('about-version').textContent = t('about.version', {version: state.version});
 $('window-behavior').textContent = t(state.trayAvailable ? 'windows.close_to_tray' : 'windows.close_to_exit');
 $('sync-title').textContent = t(state.paused ? 'sync.paused' : 'windows.sync_enabled');
 $('online').textContent = t('windows.online_count', {count: state.online});
 $('pause').textContent = t(state.paused ? 'sync.resume' : 'sync.pause');
 $('addresses').textContent = state.addresses.join(' · ') || t('windows.no_addresses');
 $('endpoint').textContent = t(state.port !== state.runningPort ? 'windows.endpoint_pending' : 'windows.endpoint', {port: state.runningPort, savedPort: state.port});
 $('notice').textContent = state.status;
 $('pair-state').textContent = t(state.pairingOpen ? 'windows.pair_open' : 'windows.pair_closed');
 $('pair').disabled = state.pairingOpen;
 $('pair-qr-panel').hidden = !state.pairingOpen || !state.pairingQr;
 if (state.pairingQr && state.pairingOpen) {
   if ($('pair-qr').getAttribute('src') !== state.pairingQr) $('pair-qr').setAttribute('src', state.pairingQr);
 } else {
   $('pair-qr').removeAttribute('src');
 }
 $('close-pair').hidden = !state.pairingOpen;
 renderPeers(state.peers, state.locale);
 if(state.pairingOpen || state.pairing) $('pair-panel').hidden = false;
 // Use an explicit completion revision: re-pairing an existing device does not
 // change the peer list, and closing the verification dialog is not success.
 if (pairingCompleted !== undefined && pairingCompleted !== state.pairingCompleted) $('pair-panel').hidden = true;
 pairingCompleted = state.pairingCompleted;
 if(!initialized) { $('port').value=String(state.port); for(const key of ['public_access','auto_start']) $(key).selected=state[key]; initialized=true; }
 if(state.pairing && state.pairing.code !== pairCode) { pairCode=state.pairing.code; $('pair-name').textContent=state.pairing.name; $('pair-code').textContent=state.pairing.code; $('pair-dialog').show(); }
 if(!state.pairing) {pairCode=null; if($('pair-dialog').open) $('pair-dialog').close();}
};
send('ready');
