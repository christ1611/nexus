/* FDS link analysis console, shared by the dashboard's risk tab and the review page's popup. One element that survives
   redraws, so the graph keeps moving and keeps its layout. Applications are circles; the ID numbers, phones and names they
   share, and their dealers, are diamonds. Colors follow the stored risk grade: nothing here says fraud is confirmed, the
   officer decides. Options: t, L, esc, statusName from the page; standard shows the "how it is judged" button (the page
   handles [data-standard]); onDealer and onOpenApp replace the default navigation. */
window.createFdsConsole = function (opts) {
  const { t, L, esc, statusName } = opts;
  const C = { fraud: '#ff6b5e', susp: '#f2a541', ok: '#5f8f9a', attr: '#5cc8d4', dealer: '#cfe3e6' };
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const root = document.createElement('div');
  root.className = 'fds';
  root.innerHTML = `<div class="fds-head"><div class="fds-brand"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#5cc8d4" stroke-width="1.6" aria-hidden="true"><circle cx="5" cy="6" r="2.5"/><circle cx="19" cy="6" r="2.5"/><circle cx="12" cy="18" r="2.5"/><path d="M7 7l3.5 9M17 7l-3.5 9M7.5 6h9"/></svg><span data-fds="title"></span></div><span class="fds-live"><i></i>LIVE</span><span class="fds-clock" data-fds="clock"></span></div>
    <section class="fds-kpis" data-fds="kpis"></section>
    <div class="fds-main"><div class="fds-stage"><canvas></canvas><div class="fds-none" data-fds="none" hidden></div><div class="fds-tools" role="group" data-fds="tools"></div><div class="fds-legend" data-fds="legend"></div><div class="fds-tip" data-fds="tip" hidden></div></div>
    <aside class="fds-side"><div class="fds-card" data-fds="detail" aria-live="polite"></div><div class="fds-card fds-feed"><h3 data-fds="feedTitle"></h3><ul data-fds="feed"></ul></div></aside></div>`;
  const part = n => root.querySelector(`[data-fds="${n}"]`);
  const cv = root.querySelector('canvas'), ctx = cv.getContext('2d'), stage = root.querySelector('.fds-stage');
  let W = 0, H = 0, nodes = [], links = [], byId = new Map(), rings = [], selected = null, hover = null, highOnly = false, paused = false, drag = null, moved = false;
  let flags = new Map(), net = null, lastKpi = null, feedSeen = null;

  function resize() {
    const dpr = Math.min(2, devicePixelRatio || 1), w = stage.clientWidth, h = stage.clientHeight;
    if (!w || !h || (w === W && h === H)) return;
    const first = !W; W = w; H = h; cv.width = w * dpr; cv.height = h * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (first) settle();
  }
  new ResizeObserver(resize).observe(stage);

  const shortNo = n => n.replace(/^APP-\d{4}-/, '');
  const statusOf = x => x.grade === 'D' ? 'fraud' : (x.highRisk ? 'susp' : 'ok');
  const stLabel = st => st === 'fraud' ? [t('위험 고객', 'Critical'), 'f'] : st === 'susp' ? [t('고위험', 'High risk'), 's'] : [t('저위험', 'Lower risk'), 'o'];
  const attrKey = l => `${l.kind === 'SAME_PHONE' ? 'phone' : l.kind === 'SAME_NAME' ? 'name' : 'id'}:${l.detail || ''}`;
  const attrLabel = key => { const [k, ...v] = key.split(':'), raw = v.join(':').replace(/^(ID|phone|name)\s*/, ''); return k === 'phone' ? t('전화 ', 'Phone ') + raw : k === 'name' ? raw : t('신분증 ', 'ID ') + raw; };
  const hotKind = k => k === 'SAME_ID' || k === 'SAME_PHONE' || k === 'DUPLICATE';

  /* Rebuild from the latest network, keeping every known node where it is. */
  function setNetwork(n) {
    const old = byId; byId = new Map(); nodes = []; links = [];
    const add = o => { const was = old.get(o.id); Object.assign(o, was ? { x: was.x, y: was.y, vx: was.vx, vy: was.vy, born: was.born } : { x: null, y: null, vx: 0, vy: 0, born: performance.now() }); byId.set(o.id, o); nodes.push(o); return o; };
    if (n) {
      n.nodes.forEach(x => add(x.type === 'dealer' ? { id: x.id, kind: 'dealer', label: x.label, raw: x } : { id: x.id, kind: 'app', st: statusOf(x), label: shortNo(x.number), raw: x, cluster: x.cluster }));
      const seen = new Set();
      n.links.forEach(l => {
        const a = byId.get(l.source), b = byId.get(l.target); if (!a || !b) return;
        if (l.kind === 'DEALER') { links.push({ a, b, hot: false, kind: l.kind, l }); return; }
        const key = attrKey(l), attr = byId.get('attr:' + key) || add({ id: 'attr:' + key, kind: 'attr', key, cluster: a.cluster, hot: hotKind(l.kind), label: '' });
        attr.hot = attr.hot || hotKind(l.kind);
        [a, b].forEach(x => { const k = attr.id + '|' + x.id; if (!seen.has(k)) { seen.add(k); links.push({ a: x, b: attr, hot: hotKind(l.kind), kind: l.kind, l }); } });
      });
      nodes.filter(x => x.kind === 'attr').forEach(x => { x.label = attrLabel(x.key); });
      rings = n.clusters;
    } else rings = [];
    nodes.forEach(x => { if (x.x == null) { const a = anchor(x), r = () => (Math.random() - .5) * 90; x.x = a.x + r(); x.y = a.y + r(); } });
    if (selected) selected = byId.get(selected.id) || null;
    if (!selected) selected = nodes.filter(x => x.kind === 'app').sort((a, b) => (b.raw.score ?? -1) - (a.raw.score ?? -1))[0] || null;
    if (W) settle(nodes.some(x => old.has(x.id)) ? 40 : 240);
  }
  /* Each ring keeps its own part of the stage; dealers sit toward the middle. */
  function anchor(x) {
    const w = W || 800, h = H || 560, n = Math.max(rings.length, 1), i = ((x.cluster || 1) - 1) % n;
    if (x.kind === 'dealer') return { x: w / 2, y: h / 2 };
    if (n === 1) return { x: w / 2, y: h / 2 };
    const a = -Math.PI / 2 + i * 2 * Math.PI / n;
    return { x: w / 2 + Math.cos(a) * w * .3, y: h / 2 + Math.sin(a) * h * .28 };
  }
  function step() {
    for (const a of nodes) { const c = anchor(a), k = a.kind === 'dealer' ? .004 : .006; a.vx += (c.x - a.x) * k; a.vy += (c.y - a.y) * k; }
    for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
      const a = nodes[i], b = nodes[j]; let dx = b.x - a.x, dy = b.y - a.y; const d2 = dx * dx + dy * dy + .01; if (d2 > 40000) continue;
      const f = 1300 / d2, d = Math.sqrt(d2); dx /= d; dy /= d; a.vx -= dx * f; a.vy -= dy * f; b.vx += dx * f; b.vy += dy * f;
    }
    for (const l of links) {
      const dx = l.b.x - l.a.x, dy = l.b.y - l.a.y, d = Math.hypot(dx, dy) || 1, f = (d - (l.kind === 'DEALER' ? 150 : 70)) * (l.kind === 'DEALER' ? .002 : .014);
      l.a.vx += dx / d * f; l.a.vy += dy / d * f; l.b.vx -= dx / d * f; l.b.vy -= dy / d * f;
    }
    for (const n of nodes) { if (n === drag) continue; n.vx *= .86; n.vy *= .86; n.x += n.vx; n.y += n.vy; n.x = Math.max(24, Math.min(W - 24, n.x)); n.y = Math.max(50, Math.min(H - 46, n.y)); }
  }
  function settle(times = 240) { for (let i = 0; i < times; i++) step(); }

  const col = n => n.kind === 'app' ? C[n.st] : n.kind === 'dealer' ? C.dealer : C.attr;
  const rad = n => n.kind === 'app' ? (n.st === 'fraud' ? 11 : n.st === 'susp' ? 9 : 7) : n.kind === 'dealer' ? 10 : 8;
  const risky = n => n.kind === 'app' ? n.st !== 'ok' : n.kind === 'attr' ? n.hot : links.some(l => (l.a === n || l.b === n) && l.a.st && l.a.st !== 'ok');
  const visible = n => !highOnly || risky(n);
  const around = n => { const s = new Set([n]); links.forEach(l => { if (l.a === n) s.add(l.b); if (l.b === n) s.add(l.a); }); return s; };
  function shape(n, r) { ctx.beginPath(); if (n.kind === 'app') ctx.arc(n.x, n.y, r, 0, 7); else { ctx.moveTo(n.x, n.y - r); ctx.lineTo(n.x + r, n.y); ctx.lineTo(n.x, n.y + r); ctx.lineTo(n.x - r, n.y); ctx.closePath(); } }
  function draw(time) {
    ctx.clearRect(0, 0, W, H);
    ctx.strokeStyle = '#1b556355'; ctx.lineWidth = 1;
    for (let x = reduce ? 0 : (time * .01) % 40; x < W; x += 40) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke(); }
    for (let y = 0; y < H; y += 40) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke(); }
    const focus = selected ? around(selected) : null;
    for (const c of rings) {
      const members = nodes.filter(n => n.cluster === c.id && visible(n)); if (members.length < 2) continue;
      const cx = members.reduce((s, n) => s + n.x, 0) / members.length, cy = members.reduce((s, n) => s + n.y, 0) / members.length;
      const R = Math.max(...members.map(n => Math.hypot(n.x - cx, n.y - cy))) + 34, high = c.severity === 'HIGH', pulse = reduce ? .5 : .5 + .5 * Math.sin(time / 500), rgb = high ? '255,107,94' : '242,165,65';
      ctx.setLineDash([6, 6]); ctx.lineDashOffset = reduce ? 0 : -time / 40; ctx.strokeStyle = `rgba(${rgb},${.25 + .25 * pulse})`; ctx.lineWidth = 1.2;
      ctx.beginPath(); ctx.arc(cx, cy, R, 0, 7); ctx.stroke(); ctx.setLineDash([]); ctx.fillStyle = `rgba(${rgb},.04)`; ctx.fill();
      const score = Math.max(0, ...members.map(n => n.raw?.score ?? 0));
      ctx.font = '700 11px SUIT Variable, sans-serif'; ctx.fillStyle = `rgb(${rgb})`; ctx.fillText(`${t('묶음', 'RING')} ${c.id} · ${t('신청', 'APPS')} ${c.applications.length} · ${t('최고 점수', 'TOP SCORE')} ${score}`, Math.max(8, cx - R * .7), Math.max(14, cy - R - 7));
    }
    for (const l of links) {
      if (!visible(l.a) || !visible(l.b)) continue;
      const dim = focus && !(focus.has(l.a) && focus.has(l.b));
      ctx.globalAlpha = dim ? .3 : (l.kind === 'DEALER' ? .5 : 1);
      ctx.strokeStyle = l.hot ? C.fraud : l.kind === 'DEALER' ? '#6b949e' : '#3f7380'; ctx.lineWidth = l.hot ? 1.6 : 1;
      if (l.hot) { ctx.shadowColor = C.fraud; ctx.shadowBlur = 8; }
      ctx.beginPath(); ctx.moveTo(l.a.x, l.a.y); ctx.lineTo(l.b.x, l.b.y); ctx.stroke(); ctx.shadowBlur = 0;
      if (l.hot && !reduce && !dim) for (let k = 0; k < 2; k++) { const p = (time / 1400 + k * .5 + (l.a.x % 7) * .13) % 1; ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(l.a.x + (l.b.x - l.a.x) * p, l.a.y + (l.b.y - l.a.y) * p, 1.8, 0, 7); ctx.fill(); }
    }
    ctx.globalAlpha = 1;
    for (const n of nodes) {
      if (!visible(n)) continue;
      const dim = focus && !focus.has(n), r = rad(n) * Math.min(1, (time - n.born) / 400 + .2), c = col(n);
      ctx.globalAlpha = dim ? .5 : 1;
      if (n.st === 'fraud' && !reduce) { const p = (time / 1200 + n.x * .002) % 1; ctx.strokeStyle = c; ctx.globalAlpha = (dim ? .25 : .6) * (1 - p); ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(n.x, n.y, r + p * 22, 0, 7); ctx.stroke(); ctx.globalAlpha = dim ? .5 : 1; }
      ctx.shadowColor = c; ctx.shadowBlur = n.st === 'ok' ? 0 : 16;
      shape(n, r); ctx.fillStyle = n.kind === 'app' ? c : '#0b3c49'; ctx.fill(); ctx.shadowBlur = 0;
      if (n.kind !== 'app') { ctx.lineWidth = 2; ctx.strokeStyle = c; ctx.stroke(); }
      if (n === selected || n === hover) { ctx.strokeStyle = '#fff'; ctx.lineWidth = 1.5; shape(n, r + 5); ctx.stroke(); }
      { ctx.font = (n.kind === 'app' ? '600 11px' : '500 10px') + ' SUIT Variable, sans-serif'; ctx.fillStyle = n.kind === 'app' ? '#e6f2f3' : n.kind === 'dealer' ? '#e6f2f3' : '#a7dce3'; ctx.fillText(n.label, n.x + r + 5, n.y + 4); }
      ctx.globalAlpha = 1;
    }
  }
  function loop(time) {
    if (root.isConnected && stage.offsetParent !== null && !document.hidden) { resize(); if (W) { if (!paused) step(); draw(time); } }
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);

  function gauge(v, c) {
    const a = Math.PI * 1.5 * (v / 100), r = 26, o = 32, s = Math.PI * .75, ex = o + r * Math.cos(s + a), ey = o + r * Math.sin(s + a), large = a > Math.PI ? 1 : 0, sx = o + r * Math.cos(s), sy = o + r * Math.sin(s);
    return `<svg width="64" height="58" viewBox="0 0 64 58" role="img" aria-label="${t('리스크 점수', 'Risk score')} ${v}"><path d="M${sx} ${sy} A${r} ${r} 0 1 1 ${o + r * Math.cos(s + Math.PI * 1.5)} ${o + r * Math.sin(s + Math.PI * 1.5)}" stroke="#1b5563" stroke-width="6" fill="none" stroke-linecap="round"/>${v > 0 ? `<path d="M${sx} ${sy} A${r} ${r} 0 ${large} 1 ${ex} ${ey}" stroke="${c}" stroke-width="6" fill="none" stroke-linecap="round"/>` : ''}<text x="32" y="37" text-anchor="middle" style="fill:${c};font-size:16px;font-weight:700">${v}</text></svg>`;
  }
  const standardBtn = () => opts.standard ? `<button type="button" class="fds-btn" data-standard="fraud">${t('판단 기준', 'How it is judged')}</button>` : '';
  function renderDetail() {
    const el = part('detail'), n = selected, head = `<h3>${t('노드 상세', 'Node detail')}</h3>`;
    if (!n) { el.innerHTML = `${head}<p style="color:var(--f-mute);margin:0;font-size:13px">${t('그래프에서 노드를 선택하세요.', 'Select a node in the graph.')}</p>`; return; }
    const nb = [...around(n)].filter(x => x !== n), apps = nb.filter(x => x.kind === 'app');
    if (n.kind === 'app') {
      const x = n.raw, [lb, cl] = stLabel(n.st), own = links.filter(l => (l.a === n || l.b === n) && l.kind !== 'DEALER');
      const rules = [...new Set([...(flags.get(x.number) || []), ...own.map(l => L(l.l))])];
      el.innerHTML = `${head}<div class="fds-name">${esc(x.name || t('고객 미연결', 'No customer yet'))}<span class="fds-chip ${cl}">${lb}</span></div>
        <div class="fds-gauge">${gauge(x.score ?? 0, col(n))}<dl class="fds-rows"><dt>${t('신청번호', 'Application')}</dt><dd>${esc(x.number)}</dd><dt>${t('등급', 'Grade')}</dt><dd>${esc(x.grade || '–')}</dd><dt>${t('상태', 'Status')}</dt><dd>${esc(statusName(x.status))}</dd><dt>${t('딜러', 'Dealer')}</dt><dd>${esc(x.dealerCode || '–')}</dd><dt>${t('연결 속성', 'Shared values')}</dt><dd>${nb.filter(y => y.kind === 'attr').length}</dd></dl></div>
        ${rules.length ? `<ul class="fds-rules">${rules.map(r => `<li>${esc(r)}</li>`).join('')}</ul>` : `<p style="color:var(--f-mute);font-size:12px;margin:0">${t('걸린 규칙 없음', 'No rules hit')}</p>`}
        <div class="fds-actions"><a class="fds-btn" data-fds-open="${esc(x.number)}" href="/review?app=${encodeURIComponent(x.number)}">${t('심사 화면 열기', 'Open in review')}</a>${standardBtn()}</div>`;
    } else {
      const bad = apps.filter(a => a.st !== 'ok').length, dealer = n.kind === 'dealer';
      const type = dealer ? t('딜러', 'Dealer') : n.key.startsWith('phone') ? t('공유 전화번호', 'Shared phone') : n.key.startsWith('name') ? t('같은 이름·생년월일', 'Same name and birth date') : t('공유 신분증 번호', 'Shared ID number');
      el.innerHTML = `${head}<div class="fds-name">${esc(dealer ? n.raw.name : n.label)}</div>
        <dl class="fds-rows" style="margin-top:10px"><dt>${t('유형', 'Type')}</dt><dd>${type}</dd>${dealer ? `<dt>${t('코드', 'Code')}</dt><dd>${esc(n.label)}</dd><dt>${t('등급', 'Tier')}</dt><dd>${n.raw.tier === 'EXCELLENT' ? t('우수 딜러', 'Excellent') : t('일반 딜러', 'Standard')}</dd>` : ''}<dt>${t('연결 신청', 'Linked applications')}</dt><dd>${apps.length}</dd><dt>${t('고위험 신청', 'High-risk')}</dt><dd style="color:${bad ? C.fraud : 'inherit'}">${bad}</dd></dl>
        <div class="fds-actions">${dealer && opts.onDealer ? `<button type="button" class="fds-btn" data-fds-dealer="${esc(n.label)}" data-fds-country="${esc(n.raw.country || 'ALL')}">${t('딜러 현황 보기', 'Dealer board')}</button>` : `<button type="button" class="fds-btn" data-fds-clear="1">${t('선택 해제', 'Clear selection')}</button>`}${standardBtn()}</div>`;
    }
  }
  function renderChrome() {
    part('title').textContent = t('FDS 링크 분석 콘솔', 'FDS link analysis console');
    part('feedTitle').textContent = t('최근 탐지 피드', 'Latest detections');
    part('tools').innerHTML = `<button type="button" class="fds-btn" data-fds-filter="all" aria-pressed="${!highOnly}">${t('전체', 'All')}</button><button type="button" class="fds-btn" data-fds-filter="high" aria-pressed="${highOnly}">${t('고위험만', 'High risk only')}</button><button type="button" class="fds-btn" data-fds-pause="1" aria-pressed="${paused}">${paused ? t('재개', 'Resume') : t('일시정지', 'Pause')}</button>`;
    const dot = (c, l, sq) => `<span><i class="${sq ? 'sq' : ''}" style="background:${c}"></i>${l}</span>`;
    part('legend').innerHTML = dot(C.fraud, t('위험 고객 (D)', 'Critical (D)')) + dot(C.susp, t('고위험 (C)', 'High risk (C)')) + dot(C.ok, t('저위험·미평가', 'Lower risk or not rated')) + dot(C.attr, t('신분증·전화·이름', 'ID, phone, name'), true) + dot(C.dealer, t('딜러', 'Dealer'), true);
    const none = part('none'); none.hidden = nodes.length > 0;
    none.textContent = !net ? t('연결망을 불러오지 못했어요.', 'The network could not be loaded.') : t(`신청 ${net.scanned}건을 대조했지만 겹치는 신분증 번호·전화번호·이름이 없어요.`, `${net.scanned} applications compared; no shared ID numbers, phones or names.`);
  }
  function renderKpis(d) {
    // A page showing one part of the network brings its own summary boxes.
    if (d.kpis) { part('kpis').innerHTML = d.kpis.map(k => `<div class="fds-kpi ${k.cls || ''}"><span>${k.label}</span><b>${k.value}</b></div>`).join(''); lastKpi = null; return; }
    const weeks = d.trend || [], week = weeks.at(-1)?.created ?? 0, mx = Math.max(1, ...weeks.map(w => w.created));
    const spark = weeks.length > 1 ? `<svg width="70" height="22" aria-hidden="true"><polyline points="${weeks.map((w, i) => `${(i * 70 / (weeks.length - 1)).toFixed(1)},${(21 - w.created / mx * 19).toFixed(1)}`).join(' ')}" fill="none" stroke="#5cc8d4" stroke-width="1.5"/></svg>` : '';
    const now = { high: d.totals.highRisk, rings: net ? net.clusters.length : 0, fraud: d.totals.fraudWarnings };
    const flash = k => lastKpi && now[k] > lastKpi[k] ? ' flash' : '';
    part('kpis').innerHTML = `<div class="fds-kpi"><span>${t('이번 주 신청', 'Applications this week')}</span><b>${week}</b>${spark}</div>
      <div class="fds-kpi hot${flash('high')}"><span>${t('고위험 탐지 (C·D)', 'High risk (C·D)')}</span><b>${now.high}</b></div>
      <div class="fds-kpi warn${flash('rings')}"><span>${t('활성 묶음', 'Active rings')}</span><b>${now.rings}</b></div>
      <div class="fds-kpi${flash('fraud')}"><span>${t('위조·사기 경고', 'Fraud warnings')}</span><b>${now.fraud}</b></div>`;
    lastKpi = now;
  }
  function renderFeed(d) {
    const when = at => { const x = new Date(at); return x.toLocaleString(LANG.locale, { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }); };
    const sev = v => v >= 70 ? C.fraud : v >= 40 ? C.susp : C.ok;
    const items = [];
    d.highRiskApplications.forEach(r => { const f = r.flags.find(x => x.fraud) || r.flags[0]; items.push({ key: 'r' + r.applicationNumber, at: r.assessedAt, app: r.applicationNumber, score: r.score, msg: `${shortNo(r.applicationNumber)} · ${f ? L(f, 'title') : t('고위험', 'high risk')}` }); });
    (net ? net.clusters : []).forEach(c => items.push({ key: 'c' + c.id + c.applications.length, at: c.latestAt, app: c.applications.at(-1), score: c.highRisk ? 90 : 50, msg: `${t('묶음', 'Ring')} ${c.id} · ${L({ summary: c.summary, summaryKo: c.summaryKo }, 'summary')}` }));
    d.reviewQueue.filter(q => !q.grade || !'CD'.includes(q.grade)).forEach(q => items.push({ key: 'q' + q.applicationNumber, at: q.waitingSince, app: q.applicationNumber, score: q.score ?? 0, msg: `${shortNo(q.applicationNumber)} · ${t('심사 대기 진입', 'ready for review')}` }));
    items.sort((a, b) => new Date(b.at) - new Date(a.at));
    const list = items.slice(0, 14), seen = new Set(list.map(x => x.key));
    const feedEl = part('feed'), feedScroll = feedEl.scrollTop;
    feedEl.innerHTML = list.map(x => `<li style="--sev:${sev(x.score)}" class="${feedSeen && !feedSeen.has(x.key) ? 'new' : ''}" data-fds-app="${esc(x.app || '')}"><time>${esc(when(x.at))}</time><span>${esc(x.msg)}</span><b>${x.score}</b></li>`).join('');
    feedEl.scrollTop = feedScroll;
    feedSeen = seen;
  }
  function pick(e) {
    const r = cv.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top; let best = null, bd = 18;
    for (const n of nodes) { if (!visible(n)) continue; const d = Math.hypot(n.x - x, n.y - y); if (d < bd) { bd = d; best = n; } }
    return [best, x, y];
  }
  cv.addEventListener('pointermove', e => {
    const [n, x, y] = pick(e), tipEl = part('tip');
    if (drag) { moved = true; drag.x = x; drag.y = y; drag.vx = drag.vy = 0; if (paused) draw(performance.now()); return; }
    hover = n; cv.style.cursor = n ? 'pointer' : 'grab';
    if (n) { tipEl.hidden = false; tipEl.style.left = Math.min(x + 14, W - 200) + 'px'; tipEl.style.top = (y + 12) + 'px'; tipEl.textContent = n.kind === 'app' ? `${n.raw.number} · ${t('점수', 'score')} ${n.raw.score ?? '–'}` : n.kind === 'dealer' ? `${n.label} · ${n.raw.name}` : n.label; } else tipEl.hidden = true;
  });
  cv.addEventListener('pointerleave', () => { hover = null; part('tip').hidden = true; });
  cv.addEventListener('pointerdown', e => { const [n] = pick(e); moved = false; if (n) { drag = n; try { cv.setPointerCapture(e.pointerId); } catch {} cv.style.cursor = 'grabbing'; } });
  const up = e => { const [n] = pick(e); if (drag && !moved && n === drag) { selected = n; renderDetail(); } else if (!drag && !n) { selected = null; renderDetail(); } drag = null; moved = false; };
  cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', () => { drag = null; moved = false; });
  root.addEventListener('click', e => {
    const f = e.target.closest('[data-fds-filter]');
    if (f) { highOnly = f.dataset.fdsFilter === 'high'; renderChrome(); return; }
    if (e.target.closest('[data-fds-pause]')) { paused = !paused; renderChrome(); return; }
    if (e.target.closest('[data-fds-clear]')) { selected = null; renderDetail(); return; }
    const dealerBtn = e.target.closest('[data-fds-dealer]');
    if (dealerBtn) { opts.onDealer(dealerBtn.dataset.fdsDealer, dealerBtn.dataset.fdsCountry); return; }
    const openLink = e.target.closest('[data-fds-open]');
    if (openLink && opts.onOpenApp) { e.preventDefault(); opts.onOpenApp(openLink.dataset.fdsOpen); return; }
    const li = e.target.closest('[data-fds-app]');
    if (li && li.dataset.fdsApp) { const n = nodes.find(x => x.kind === 'app' && x.raw.number === li.dataset.fdsApp); if (n) { selected = n; renderDetail(); } else if (opts.onOpenApp) opts.onOpenApp(li.dataset.fdsApp); else location.href = '/review?app=' + encodeURIComponent(li.dataset.fdsApp); }
  });
  setInterval(() => { if (root.isConnected && !document.hidden) part('clock').textContent = new Date().toLocaleString(LANG.locale, { hour12: false }); }, 1000);

  return {
    /* Called after each redraw of the tab: put the console back in its slot and refresh what changed. */
    mount(d, network, slot) {
      if (slot) slot.appendChild(root);
      net = network;
      flags = new Map(d.highRiskApplications.map(r => [r.applicationNumber, r.flags.map(f => L(f, 'title'))]));
      const sig = network ? JSON.stringify([network.nodes.map(x => [x.id, x.grade, x.status, x.score]), network.links.length]) : '';
      if (sig !== root.dataset.sig) { root.dataset.sig = sig; setNetwork(network); }
      part('clock').textContent = new Date().toLocaleString(LANG.locale, { hour12: false });
      renderChrome(); renderKpis(d); renderFeed(d); renderDetail();
      nodes.filter(x => x.kind === 'attr').forEach(x => { x.label = attrLabel(x.key); });
    },
    /* Select an application's node; false when it is not linked to anything. */
    select(number) {
      const n = nodes.find(x => x.kind === 'app' && x.raw.number === number);
      selected = n || null; renderDetail();
      return !!n;
    },
  };
};
