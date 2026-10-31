import '@material/web/button/filled-button.js';
import '@material/web/button/filled-tonal-button.js';
import '@material/web/button/outlined-button.js';
import '@material/web/button/text-button.js';
import '@material/web/switch/switch.js';
import '@material/web/textfield/outlined-text-field.js';
import '@material/web/dialog/dialog.js';
const $ = id => document.getElementById(id);
const send = (action, values = {}) => window.ipc.postMessage(JSON.stringify({action,...values}));
let initialized = false, pairCode = null;
const showPage = page => {
  for (const other of ['sync','settings','about']) $(other).hidden = other !== page;
  for (const other of ['sync','settings']) $('nav-'+other).setAttribute('aria-current', (other === page || (page === 'about' && other === 'settings')) ? 'page' : 'false');
  window.scrollTo(0, 0);
};
for (const page of ['sync','settings']) $('nav-'+page).onclick = () => showPage(page);
$('open-about').onclick = () => showPage('about');
$('back-about').onclick = () => showPage('settings');
$('pause').onclick = () => send('pause');
$('quit').onclick = () => send('quit');
$('pair').onclick = () => send('pair');
$('close-pair').onclick = () => send('closePair');
$('save').onclick = () => {
 const port = Number($('port').value);
 if (!Number.isInteger(port) || port < 1 || port > 65535) { $('port').error = true; $('port').errorText = '请输入 1–65535 之间的整数'; return; }
 $('port').error = false;
 send('save', {port,...Object.fromEntries(['public_access','bluetooth','auto_start'].map(key => [key,$(key).selected]))});
};
$('revoke').onclick = () => $('revoke-dialog').show();
$('cancel-revoke').onclick = () => $('revoke-dialog').close();
$('confirm-revoke').onclick = () => { send('revoke'); $('revoke-dialog').close(); };
for(const [id,approved] of [['approve',true],['reject',false]]) $(id).onclick = () => { send('confirmPair',{approved}); $('pair-dialog').close(); };
$('pair-dialog').addEventListener('cancel', () => send('confirmPair',{approved:false}));
window.updateState = state => {
 $('about-version').textContent = `版本 ${state.version}`;
 $('window-behavior').textContent = state.trayAvailable ? '关闭窗口后继续同步' : '托盘不可用，关闭窗口将退出';
 $('sync-title').textContent = state.paused ? '同步已暂停' : '同步已开启';
 $('online').textContent = `${state.online} 台设备在线`;
 $('pause').textContent = state.paused ? '恢复同步' : '暂停同步';
 $('addresses').textContent = state.addresses.join(' · ') || '暂未找到局域网地址';
 $('endpoint').textContent = `当前监听端口 ${state.runningPort}` + (state.port !== state.runningPort ? ` · 已保存端口 ${state.port}，重启后生效` : '');
 $('notice').textContent = state.status;
 $('pair-state').textContent = state.pairingOpen ? '配对已开启，等待手机连接（最长 120 秒）' : '配对未开启';
 $('pair').disabled = state.pairingOpen;
 $('close-pair').hidden = !state.pairingOpen;
 $('devices').replaceChildren(...(state.peers.length ? state.peers : ['尚无已配对设备']).map(name => {const li=document.createElement('li');li.textContent=name;return li;}));
 if(!initialized) { $('port').value=String(state.port); for(const key of ['public_access','bluetooth','auto_start']) $(key).selected=state[key]; initialized=true; }
 if(state.pairing && state.pairing.code !== pairCode) { pairCode=state.pairing.code; $('pair-name').textContent=state.pairing.name; $('pair-code').textContent=state.pairing.code; $('pair-dialog').show(); }
 if(!state.pairing) {pairCode=null; if($('pair-dialog').open) $('pair-dialog').close();}
};
send('ready');
