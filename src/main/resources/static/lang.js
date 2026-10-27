/* Back-office language switch (Korean / English), shared by the dashboard and the review page.
   The choice is remembered per browser; a floating button at the bottom right toggles it. Pages render strings
   through LANG.t(ko, en) and LANG.L(obj) (server objects that carry both `label` and `labelKo`), and static markup
   uses data-ko / data-en (and data-ko-placeholder / data-ko-title) so it can be swapped in place. */
window.LANG = (function () {
  const KEY = 'bo-lang';
  let lang = 'ko';
  try { lang = localStorage.getItem(KEY) || ((navigator.language || 'ko').toLowerCase().startsWith('ko') ? 'ko' : 'en'); } catch {}
  if (lang !== 'en') lang = 'ko';
  const listeners = [];
  const t = (ko, en) => lang === 'en' ? en : ko;
  const L = (o, base = 'label') => o == null ? '' : (lang === 'en' ? (o[base] ?? o[base + 'Ko'] ?? '') : (o[base + 'Ko'] ?? o[base] ?? ''));
  const ATTRS = [['placeholder', 'Placeholder'], ['title', 'Title'], ['aria-label', 'AriaLabel']];
  function apply(root = document) {
    root.querySelectorAll('[data-ko]').forEach(el => { el.innerHTML = t(el.dataset.ko, el.dataset.en ?? el.dataset.ko); });
    ATTRS.forEach(([attr, k]) => root.querySelectorAll(`[data-ko-${attr}]`).forEach(el => el.setAttribute(attr, t(el.dataset['ko' + k], el.dataset['en' + k] ?? el.dataset['ko' + k]))));
    document.documentElement.lang = lang;
    const b = document.getElementById('lang-toggle');
    if (b) { b.textContent = lang === 'ko' ? 'English' : '한국어'; b.title = lang === 'ko' ? 'Switch to English' : '한국어로 보기'; b.setAttribute('aria-label', b.title); }
  }
  function set(l) {
    lang = l === 'en' ? 'en' : 'ko';
    try { localStorage.setItem(KEY, lang); } catch {}
    apply();
    listeners.forEach(f => { try { f(lang); } catch (e) { console.error(e); } });
  }
  function mount() {
    if (document.getElementById('lang-toggle')) return;
    const s = document.createElement('style');
    s.textContent = '#lang-toggle{position:fixed;right:16px;bottom:16px;z-index:40;font:inherit;font-weight:700;font-size:13px;padding:9px 16px 9px 14px;border-radius:999px;border:1px solid var(--border,#e1e5ee);background:var(--surface,#fff);color:var(--brand,#0a31a8);box-shadow:0 2px 10px rgba(13,45,119,.18);cursor:pointer;transition:background .15s,color .15s}'
      + '#lang-toggle::before{content:"🌐";margin-right:6px}'
      + '#lang-toggle:hover{background:var(--brand,#0a31a8);color:#fff;border-color:transparent}'
      + '#lang-toggle:focus-visible{outline:none;box-shadow:0 0 0 3px var(--brand-tint,#eaf0ff)}'
      + '@media print{#lang-toggle{display:none!important}}';
    document.head.appendChild(s);
    const b = document.createElement('button'); b.type = 'button'; b.id = 'lang-toggle';
    b.addEventListener('click', () => set(lang === 'ko' ? 'en' : 'ko'));
    document.body.appendChild(b);
    apply();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
  return { get lang() { return lang; }, get locale() { return lang === 'en' ? 'en-GB' : 'ko-KR'; }, t, L, set, apply, onChange: f => listeners.push(f) };
})();
