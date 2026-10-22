(() => {
  const out = {};
  const vis = (e) => { const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0'; };
  const desc = (e) => { let s = e.tagName.toLowerCase(); if (e.id) s += '#' + e.id; if (e.className && typeof e.className === 'string') s += '.' + e.className.trim().split(/\s+/).slice(0, 3).join('.'); const t = (e.getAttribute('aria-label') || e.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 30); return s + (t ? ` "${t}"` : ''); };
  const W = innerWidth;
  out.docOverflow = document.documentElement.scrollWidth - W;
  out.bodyOverflow = document.body.scrollWidth - W;
  const over = [];
  document.querySelectorAll('body *').forEach((e) => { if (!vis(e)) return; const r = e.getBoundingClientRect(); if (r.right > W + 1 && !e.closest('.scroller,.chips,.cast,.hero,.det-hero,.tabs,.seasonbar,[data-scroll]')) over.push(desc(e) + ' r=' + Math.round(r.right)); });
  out.offscreen = over.slice(0, 8);
  const small = [];
  document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,[role=tab],[role=switch],summary,[tabindex="0"]').forEach((e) => {
    if (!vis(e)) return; const r = e.getBoundingClientRect();
    if (e.closest('.skip')) return;
    if ((r.height < 44 || r.width < 44) && !(e.tagName === 'A' && getComputedStyle(e).display === 'inline')) small.push(desc(e) + ` ${Math.round(r.width)}x${Math.round(r.height)}`);
  });
  out.smallTargets = small;
  const noname = [];
  document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,[role=tab],[role=switch],summary').forEach((e) => {
    if (!vis(e)) return;
    let n = e.getAttribute('aria-label') || '';
    const lb = e.getAttribute('aria-labelledby'); if (lb) n += lb.split(' ').map((i) => document.getElementById(i)?.textContent || '').join(' ');
    if (!n.trim()) n = e.innerText || e.textContent || '';
    if (!n.trim() && e.labels?.length) n = e.labels[0].textContent;
    if (!n.trim()) n = e.title || e.placeholder || '';
    if (!n.trim()) n = [...e.querySelectorAll('img')].map((i) => i.alt).join('');
    if (!n.trim()) noname.push(desc(e));
  });
  out.noName = noname;
  out.imgNoAlt = [...document.querySelectorAll('img')].filter((i) => !i.hasAttribute('alt')).map((i) => i.src.slice(-40));
  out.landmarks = { header: document.querySelectorAll('header').length, nav: document.querySelectorAll('nav').length, main: document.querySelectorAll('main').length, h1: [...document.querySelectorAll('h1')].filter(vis).map((e) => e.textContent.trim().slice(0, 30)), headings: [...document.querySelectorAll('h1,h2,h3,h4')].filter(vis).map((e) => e.tagName[1]).join('') };
  // contrast
  const parse = (c) => { const m = c.match(/rgba?\(([^)]+)\)/); if (!m) return null; const p = m[1].split(/[ ,\/]+/).filter(Boolean).map(Number); return { r: p[0], g: p[1], b: p[2], a: p[3] === undefined ? 1 : p[3] }; };
  const lum = (c) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; }; return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); };
  const blend = (top, bot) => ({ r: top.r * top.a + bot.r * (1 - top.a), g: top.g * top.a + bot.g * (1 - top.a), b: top.b * top.a + bot.b * (1 - top.a), a: 1 });
  const bgOf = (e) => { const stack = []; let n = e; let img = false; while (n && n.nodeType === 1) { const cs = getComputedStyle(n); if (cs.backgroundImage !== 'none' && n !== document.body) img = true; const c = parse(cs.backgroundColor); if (c && c.a > 0) { stack.push(c); if (c.a >= 1) break; } n = n.parentElement; } let base = parse(getComputedStyle(document.documentElement).getPropertyValue('--bg')) || { r: 20, g: 17, b: 15, a: 1 }; const bgv = getComputedStyle(document.body).backgroundColor; const bc = parse(bgv); if (bc && bc.a > 0) base = bc; if (stack.length && stack[stack.length - 1].a >= 1) base = stack.pop(); while (stack.length) base = blend(stack.pop(), base); return { c: base, img }; };
  const low = []; const seen = new Set();
  document.querySelectorAll('body *').forEach((e) => {
    if (!vis(e)) return; const tn = [...e.childNodes].filter((n) => n.nodeType === 3 && n.textContent.trim()); if (!tn.length) return;
    const cs = getComputedStyle(e); const fg0 = parse(cs.color); if (!fg0) return; const { c: bg, img } = bgOf(e); const fg = fg0.a < 1 ? blend(fg0, bg) : fg0;
    const L1 = lum(fg), L2 = lum(bg); const ratio = (Math.max(L1, L2) + 0.05) / (Math.min(L1, L2) + 0.05);
    const size = parseFloat(cs.fontSize); const bold = parseInt(cs.fontWeight) >= 700; const large = size >= 24 || (size >= 18.66 && bold);
    const need = large ? 3 : 4.5; const op = parseFloat(cs.opacity);
    if (ratio < need) { const k = desc(e) + ratio.toFixed(2); if (!seen.has(k)) { seen.add(k); low.push(`${desc(e)} ${ratio.toFixed(2)} (<${need}) ${cs.color} on rgb(${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)})${img ? ' [bg-image]' : ''}`); } }
  });
  out.lowContrast = low.slice(0, 25);
  out.fonts = [...new Set([...document.querySelectorAll('body *')].filter(vis).filter((e) => [...e.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())).map((e) => parseFloat(getComputedStyle(e).fontSize)))].filter((s) => s < 12).sort();
  out.dupIds = (() => { const c = {}; document.querySelectorAll('[id]').forEach((e) => { c[e.id] = (c[e.id] || 0) + 1; }); return Object.keys(c).filter((k) => c[k] > 1); })();
  out.ariaBad = [...document.querySelectorAll('[aria-labelledby],[aria-describedby],[aria-controls]')].filter((e) => ['aria-labelledby', 'aria-describedby', 'aria-controls'].some((a) => e.hasAttribute(a) && e.getAttribute(a).split(' ').some((i) => i && !document.getElementById(i)))).map(desc);
  out.dom = document.getElementsByTagName('*').length;
  return out;
})()
