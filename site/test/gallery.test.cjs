const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync, existsSync } = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

const publicDir = path.resolve(__dirname, '../src/main/resources/public');
const html = readFileSync(path.join(publicDir, 'index.html'), 'utf8').replace('{{GCS_BUCKET}}', '__local__');
const script = readFileSync(path.join(publicDir, 'assets/site.js'), 'utf8');
const artists = ['Gemini', 'Claude', 'ChatGPT', 'Grok', 'DeepSeek', 'Mistral'];
const manifest = { entries: Array.from({ length: 5 }, (_, week) => ({
  version: `2026-W${41 - week}`, date: '2026-09-28 to 2026-10-05',
  highlights: ['A headline'], sourceUrl: 'https://example.com/news',
  pieces: artists.map(artist => ({
    artist, model: `${artist} test model`, imageUrl: `/images/${41 - week}-${artist}.png`,
    thumbnailUrl: `/thumbs/${41 - week}-${artist}.jpg`,
    prompt: 'An image about change.', rationale: 'I see a changing world.',
    citations: [{ quote: 'changing world', headline: 'A headline', source: 'News', url: 'https://example.com/story' }]
  }))
})) };
const settle = () => new Promise(resolve => setTimeout(resolve, 20));

// Escape on a modal dialog fires "cancel", and Chrome then closes it WITHOUT ever firing
// "close" - including when the page cancels that event and calls close() itself from inside the
// dispatch. Modelling that exactly is the point: a stub that always fires "close" hides the
// whole class of bug where the fragment is only cleared from a "close" listener, which is what
// shipped and left a stale permalink in the address bar.
function pressEscape(window, dialog) {
  const event = new window.Event('cancel', { cancelable: true });
  window.__dispatchingCancel = true;
  try { dialog.dispatchEvent(event); } finally { window.__dispatchingCancel = false; }
  if (!event.defaultPrevented) dialog.open = false;
}

async function gallery(t, options = {}) {
  const dom = new JSDOM(html, { url: `https://machinewitness.art/${options.hash || ''}`, runScripts: 'outside-only' });
  const { window } = dom;
  // jsdom models the DOM and history, but not the native dialog's top layer.
  window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  window.HTMLDialogElement.prototype.close = function () {
    this.open = false;
    if (window.__dispatchingCancel) return;   // see pressEscape
    window.setTimeout(() => this.dispatchEvent(new window.Event('close')), 0);
  };
  window.fetch = options.fetch || (async () => ({ ok: true, json: async () => options.manifest || manifest }));
  if (options.clipboard) Object.defineProperty(window.navigator, 'clipboard', { value: options.clipboard });
  window.eval(script);
  await settle();
  t.after(() => window.close());
  return { window, document: window.document, $: selector => window.document.querySelector(selector) };
}

test('renders published pieces, accessible labels, lazy archive images, and collection metadata', async t => {
  const { document, $ } = await gallery(t);
  assert.equal(document.querySelectorAll('.showcase-tile').length, 6);
  assert.equal(document.querySelectorAll('.card').length, 12);
  assert.match($('#latest-meta').textContent, /2026-W41 \/ 6 pieces/);
  assert.match($('#archive-count').textContent, /12 of 24/);
  assert.equal($('#hero-art').getAttribute('aria-busy'), 'false');
  assert.equal($('.card img').loading || $('.card img').getAttribute('loading'), 'lazy');
  assert.match($('.showcase-tile').getAttribute('aria-label'), /artwork and explanation/);
  assert.equal(JSON.parse($('#structured-data').textContent).itemListElement.length, 6);
});

test('filtering resets pagination and the viewer follows only the selected model', async t => {
  const { window, document, $ } = await gallery(t);
  $('.load-more').click();
  assert.equal(document.querySelectorAll('.card').length, 24);
  assert.equal(document.activeElement, document.querySelectorAll('.card')[12]);
  $('#artist-filter').value = 'Claude';
  $('#artist-filter').dispatchEvent(new window.Event('change'));
  assert.equal(document.querySelectorAll('.card').length, 4);
  assert.match($('#archive-count').textContent, /4 of 4 earlier pieces by Claude/);
  assert.equal($('.load-more'), null);
  $('.card').click();
  assert.match($('#piece-title').textContent, /Claude/);
  assert.equal($('#piece-position').textContent, '1 / 4');
  $('#piece-next').click();
  assert.match($('#piece-title').textContent, /Claude/);
  assert.equal(window.location.hash, '#2026-W39-claude');
  $('#artist-filter').value = '';
  $('#artist-filter').dispatchEvent(new window.Event('change'));
  assert.equal(document.querySelectorAll('.card').length, 12);
});

test('viewer navigation updates permalinks without growing history and close returns to the archive anchor', async t => {
  const { window, $ } = await gallery(t, { hash: '#archive' });
  $('.showcase-tile').click();
  const historyLength = window.history.length;
  assert.equal($('#piece-dialog').open, true);
  assert.equal($('#piece-prev').disabled, true);
  $('#piece-next').click();
  assert.equal(window.history.length, historyLength);
  assert.equal(window.location.hash, '#2026-W41-claude');
  assert.equal($('#piece-position').textContent, '2 / 6');
  $('#piece-dialog').dispatchEvent(new window.KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
  assert.equal(window.location.hash, '#2026-W41-gemini');
  $('.dialog-close').click();
  await settle();
  assert.equal($('#piece-dialog').open, false);
  assert.equal(window.location.hash, '#archive');
  window.history.forward();
  await settle();
  assert.equal($('#piece-dialog').open, true);
  window.history.back();
  await settle();
  assert.equal($('#piece-dialog').open, false);
  assert.equal(window.location.hash, '#archive');
});

test('deep links open pieces outside the first archive page; Escape clears direct links', async t => {
  const { window, $ } = await gallery(t, { hash: '#2026-W37-mistral' });
  assert.equal($('#piece-dialog').open, true);
  assert.match($('#piece-title').textContent, /Mistral/);
  assert.equal($('#piece-position').textContent, '6 / 6');
  assert.equal($('#piece-next').disabled, true);
  assert.equal($('[data-key="2026-W37-mistral"]'), null);
  pressEscape(window, $('#piece-dialog'));
  await settle();
  assert.equal($('#piece-dialog').open, false);
  assert.equal(window.location.hash, '');
});

test('Escape clears the fragment on a piece opened from the page, not just a direct link', async t => {
  const { window, $ } = await gallery(t);
  $('.showcase-tile').click();
  assert.equal(window.location.hash, '#2026-W41-gemini');
  pressEscape(window, $('#piece-dialog'));
  await settle();
  assert.equal($('#piece-dialog').open, false);
  // The stale fragment is the regression: reloading or sharing it reopened a dismissed piece.
  assert.equal(window.location.hash, '');
  // Exactly one entry back. Clearing from both "cancel" and "close" must not pop twice, so one
  // step forward has to land back on the piece rather than somewhere past it.
  window.history.forward();
  await settle();
  assert.equal(window.location.hash, '#2026-W41-gemini');
});

test('arrow keys still work after a navigation button disables itself at either end', async t => {
  const { window, $ } = await gallery(t);
  $('.showcase-tile').click();
  const next = $('#piece-next');
  while (!next.disabled) { next.focus(); next.click(); }
  assert.equal($('#piece-position').textContent, '6 / 6');
  // Focus must stay inside the dialog. On <body> the dialog's keydown listener never fires and
  // arrow navigation dies at the end of every collection.
  assert.equal($('#piece-dialog').contains(window.document.activeElement), true);
  assert.equal(window.document.activeElement, $('#piece-prev'));
  window.document.activeElement.dispatchEvent(
    new window.KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
  assert.equal($('#piece-position').textContent, '5 / 6');
});

test('copy link shares the current piece and reports success', async t => {
  let copied;
  const { $ } = await gallery(t, { clipboard: { writeText: async text => { copied = text; } } });
  $('.showcase-tile').click();
  $('#piece-next').click();
  $('#copy-piece-link').click();
  await settle();
  assert.equal(copied, 'https://machinewitness.art/#2026-W41-claude');
  assert.equal($('#copy-piece-link').textContent, 'Link copied');
});

test('clipboard failure exposes a selectable permalink', async t => {
  const { document, $ } = await gallery(t);
  $('.showcase-tile').click();
  $('#copy-piece-link').click();
  await settle();
  assert.equal($('#share-fallback').hidden, false);
  assert.equal($('#piece-share-url').value, 'https://machinewitness.art/#2026-W41-gemini');
  assert.equal(document.activeElement, $('#piece-share-url'));
});

test('network failure is distinct from an empty gallery and retry recovers', async t => {
  let calls = 0;
  const { $ } = await gallery(t, { fetch: async () => {
    if (++calls === 1) throw new Error('offline');
    return { ok: true, json: async () => manifest };
  } });
  assert.match($('#hero-art').textContent, /could not load/);
  assert.equal($('#hero-art').getAttribute('aria-busy'), 'false');
  $('.retry-button').click();
  await settle();
  assert.ok($('.showcase-tile'));
  assert.equal($('.retry-button'), null);
});

test('empty and partial collections render without claiming six pieces were published', async t => {
  const empty = await gallery(t, { manifest: { entries: [] } });
  assert.match(empty.$('#hero-art').textContent, /No artwork/);
  assert.equal(empty.$('#artist-filter').disabled, true);
  const partial = structuredClone(manifest);
  partial.entries = partial.entries.slice(0, 1);
  partial.entries[0].pieces = partial.entries[0].pieces.slice(0, 3);
  const { document, $ } = await gallery(t, { manifest: partial });
  assert.equal(document.querySelectorAll('.showcase-tile').length, 3);
  assert.match($('#latest-meta').textContent, /3 pieces/);
  assert.match($('#archive-count').textContent, /first collection/);
});

test('malformed URL fragments do not break the gallery', async t => {
  const { $ } = await gallery(t, { hash: '#%E0%A4%A' });
  assert.ok($('.showcase-tile'));
  assert.equal($('#piece-dialog').open, false);
  assert.equal($('.retry-button'), null);
});

test('rationales precede collapsed prompts, retain source links, and escape model-generated markup', async t => {
  const data = structuredClone(manifest);
  data.entries[0].pieces[0].rationale += ' <img src=x onerror=alert(1)> & truth';
  const { $ } = await gallery(t, { manifest: data });
  $('.showcase-tile').click();
  assert.equal($('.rationale img'), null);
  assert.match($('.rationale').textContent, /<img src=x/);
  assert.equal($('.citation').href, 'https://example.com/story');
  assert.equal($('.prompt-details').open, false);
  assert.ok($('.rationale').compareDocumentPosition($('.prompt-details')) & 4);
});

test('all local asset references exist and document IDs are unique', async t => {
  const { document } = await gallery(t);
  const ids = [...document.querySelectorAll('[id]')].map(el => el.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const element of document.querySelectorAll('[src], link[href]')) {
    const url = element.getAttribute('src') || element.getAttribute('href');
    if (url.startsWith('/assets/')) assert.ok(existsSync(path.join(publicDir, url.split('?')[0])), url);
  }
});
