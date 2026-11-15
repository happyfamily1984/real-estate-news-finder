import * as cheerio from 'cheerio';

// "1,234" "조회 1.2만" "[12]" "(3)" "2.5k" -> 숫자. 숫자가 없으면 null
export function parseCount(text) {
  if (text == null) return null;
  const s = String(text).replace(/,/g, '').trim();
  const m = s.match(/(\d+(?:\.\d+)?)\s*(만|천|k|K|m|M)?/);
  if (!m) return null;
  const n = parseFloat(m[1]);
  const mult = { 만: 10000, 천: 1000, k: 1000, K: 1000, m: 1e6, M: 1e6 }[m[2]] ?? 1;
  return Math.round(n * mult);
}

function cleanTitle(text) {
  return text
    .replace(/\s+/g, ' ')
    .replace(/\s*[\[(]\d+[\])]\s*$/, '') // 끝에 붙은 댓글수 "[12]" 제거
    .trim();
}

export function parseList(html, source) {
  const $ = cheerio.load(html);
  const sel = source.selectors;
  const posts = [];
  const seen = new Set();

  $(sel.row).each((_, el) => {
    const row = $(el);
    if (sel.exclude && row.is(sel.exclude)) return;

    const titleEl = row.find(sel.title).first();
    if (!titleEl.length) return;

    // 제목 요소 안의 댓글수 표시 등은 빼고 제목만 추출
    const titleClone = titleEl.clone();
    if (sel.comments) titleClone.find(sel.comments.split(' ').pop()).remove();
    let title = cleanTitle(titleClone.text());
    if (source.titleStrip) title = title.replace(new RegExp(source.titleStrip), '').trim();
    if (!title) return;

    const linkEl = sel.link ? row.find(sel.link).first() : titleEl.is('a') ? titleEl : titleEl.closest('a');
    const href = linkEl.attr('href') || titleEl.find('a').attr('href');
    if (!href || href.startsWith('javascript')) return;

    let url;
    try {
      const u = new URL(href, source.url);
      const keep = new URLSearchParams();
      for (const k of source.keepParams ?? []) if (u.searchParams.has(k)) keep.set(k, u.searchParams.get(k));
      u.search = keep.toString();
      u.hash = '';
      url = u.toString();
    } catch {
      return;
    }
    if (seen.has(url)) return;
    seen.add(url);

    const num = (key) => (sel[key] ? parseCount(row.find(sel[key]).first().text()) : null);

    posts.push({
      id: `${source.id}:${url}`,
      source: source.id,
      title,
      url,
      views: num('views'),
      comments: num('comments'),
      likes: num('likes'),
    });
  });

  return posts;
}
