import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createTranslator} from './i18n.mjs';

const load = name => JSON.parse(readFileSync(new URL(`../../../locales/${name}.json`, import.meta.url), 'utf8'));
const catalog = {'en-US': load('en-US'), 'zh-Hans': load('zh-Hans'), 'qps-keys': load('qps-keys'), partial: {messages: {'nav.sync': 'translated'}}};
test('test language and partial translations display exactly the missing key', () => {
  for (const locale of ['qps-keys', 'partial', 'unknown']) {
    const {t} = createTranslator(catalog, locale);
    assert.equal(t('sync.connected', {transport: 'private device'}), 'sync.connected');
  }
  assert.equal(createTranslator(catalog, 'partial').t('nav.sync'), 'translated');
  assert.equal(createTranslator(catalog, 'zh-Hans').t('unknown.key'), 'unknown.key');
});
test('messages can be rerendered and parameters are treated as literal text', () => {
  const value = JSON.stringify({csyncKey: 'sync.connected', args: {transport: JSON.stringify({csyncKey: 'transport.tcp', args: {host: '<script>{device}$1🙂'}})}});
  assert.equal(createTranslator(catalog, 'zh-Hans').message(value), '已连接 · 网络 · <script>{device}$1🙂');
  assert.equal(createTranslator(catalog, 'qps-keys').message(value), 'sync.connected');
});
test('plural categories use the selected language', () => {
  const {t} = createTranslator({en: {messages: {devices: {one: '{count} device', other: '{count} devices'}}}}, 'en');
  assert.equal(t('devices', {count: 1}), '1 device');
  assert.equal(t('devices', {count: 2}), '2 devices');
});
test('US English catalog renders device counts and nested states', () => {
  const {t, message} = createTranslator(catalog, 'en-US');
  assert.equal(t('windows.online_count', {count: 0}), '0 devices online');
  assert.equal(t('windows.online_count', {count: 1}), '1 device online');
  assert.equal(t('windows.online_count', {count: 2}), '2 devices online');
  assert.equal(message(JSON.stringify({csyncKey: 'sync.connected', args: {transport: JSON.stringify({csyncKey: 'transport.tcp', args: {host: '{device} $1 🙂'}})}})), 'Connected · Network · {device} $1 🙂');
});
