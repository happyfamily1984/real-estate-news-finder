// RSS / Atom 피드 수집 (뉴스, 정부 보도자료)
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';
import { isRelevant } from './topics.js';

const MAX_AGE_MS = 48 * 60 * 60 * 1000;

function stripHtml(s) {
  return cheerio.load(`<div>${s}</div>`)('div').text().replace(/\s+/g, ' ').trim();
}

/** keepOnly를 주면 그 파라미터만 남김 (검색어·순번 같은 꼬리표 때문에 같은 글이 다른 주소가 되지 않게) */
function cleanUrl(link, keepOnly) {
  const u = new URL(link.replace(/&#38;|&amp;/g, '&'));
  const kept = new URLSearchParams();
  for (const [k, v] of u.searchParams) if (keepOnly ? keepOnly.includes(k) : !/^utm_|^fbclid$|^ref$|^from$|^oc$/i.test(k)) kept.set(k, v);
  u.search = kept.toString();
  u.hash = '';
  return u.toString();
}

/**
 * 피드 XML → 글 배열.
 * source.filter      : true면 부동산 관련 제목만 남김 (경제 전체 피드 등)
 * source.outletFromTitle : "제목 - 매체명" 형식(구글 뉴스)에서 매체명을 분리
 */
export function parseFeed(xml, source, now = Date.now()) {
  const $ = cheerio.load(xml, { xmlMode: true });
  const out = [];
  const seen = new Set();
  $('item, entry').each((_, el) => {
    const it = $(el);
    let title = stripHtml(it.children('title').first().text());
    const linkEl = it.children('link').first();
    const link = (linkEl.text().trim() || linkEl.attr('href') || it.attr('rdf:about') || '').trim();
    if (!title || !/^https?:\/\//.test(link)) return;
    if (/^\[?(광고|AD|PR|부고|인사|포토)\]?[\s:：]/i.test(title)) return;

    let outlet = null;
    if (source.outletFromTitle) {
      outlet = it.children('source').first().text().trim() || null;
      const m = title.match(/^(.*)\s[-–|]\s([^-–|]{1,25})$/);
      if (m) { title = m[1].trim(); outlet = outlet || m[2].trim(); }
    }
    if (source.filter && !isRelevant(title)) return;

    const dateText = it.children('pubDate, published, updated, dc\\:date, date').first().text().trim();
    const t = dateText ? Date.parse(dateText) : NaN;
    const publishedAt = Number.isFinite(t) && t <= now + 3600_000 ? Math.min(t, now) : null;
    if (publishedAt && now - publishedAt > (source.maxAgeH ? source.maxAgeH * 3600_000 : MAX_AGE_MS)) return;

    let url;
    try { url = cleanUrl(link, source.keepOnly); } catch { return; }
    if (seen.has(url)) return;
    seen.add(url);

    // 경기도 피드는 요약이 <contents>에 들어 있음. 설명이 너무 짧으면(부서명 등) 본문 앞부분을 씀
    let desc = source.outletFromTitle ? '' : stripHtml(it.children('description, summary').first().text());
    if (!source.outletFromTitle && desc.length < 20) desc = stripHtml(it.children('contents, content\\:encoded').first().text()) || desc;
    const post = { id: `${source.id}:${url}`, source: source.id, title, url, views: null, comments: null, likes: null, publishedAt };
    if (outlet) post.outlet = outlet;
    if (desc && desc !== title) post.summary = desc.slice(0, 200);
    out.push(post);
  });
  return out.slice(0, source.limit ?? 40);
}

const READER_UA = 'Mozilla/5.0 (compatible; RSS reader)';

/** source.feed(주소 또는 주소 배열)를 차례로 시도. 사이트에 따라 브라우저 UA를 막는 곳이 있어 리더 UA로도 시도 */
export async function fetchFeed(source) {
  const errors = [];
  for (const url of [].concat(source.feed)) {
    for (const userAgent of [undefined, READER_UA]) {
      try {
        const xml = await fetchHtml(url, { ...(userAgent ? { userAgent } : {}), cookies: !!source.cookies });
        if (!/<(rss|feed|rdf:RDF)[\s>]/i.test(xml.slice(0, 3000))) throw new Error('피드 형식이 아님');
        const posts = parseFeed(xml, source);
        if (posts.length === 0) console.log(`[feed] ${source.id}: 0개. item ${(xml.match(/<item[\s>]/g) || []).length}개, 첫 날짜 ${(xml.match(/<pubDate>([^<]*)/) || [])[1] ?? '없음'}`);
        return posts;
      } catch (e) {
        errors.push(`${new URL(url).hostname} ${e.message}`);
      }
    }
  }
  throw new Error([...new Set(errors)].join(' / '));
}

// 구글 뉴스는 요청이 몰리면 503을 돌려줌 → 모든 검색을 한 줄로 세워 간격을 두고, 실패하면 한 번 더 시도
let googleGate = Promise.resolve();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function googleFetch(url, gapMs) {
  const run = googleGate.then(async () => {
    try {
      return await fetchHtml(url);
    } catch (e) {
      if (!/HTTP (429|503)/.test(e.message)) throw e;
      await sleep(gapMs * 4);
      return fetchHtml(url);
    }
  });
  googleGate = run.catch(() => {}).then(() => sleep(gapMs));
  return run;
}

/** 구글 뉴스 검색 RSS: 여러 검색어 결과를 합침 (언론사 여러 곳을 한 번에) */
export async function fetchGoogleNews(source, { gapMs = 1500 } = {}) {
  const all = [];
  const seen = new Set();
  let lastError = null;
  for (const q of source.queries) {
    const feed = `https://news.google.com/rss/search?q=${encodeURIComponent(`${q} when:${source.windowDays ?? 1}d`)}&hl=ko&gl=KR&ceid=KR:ko`;
    try {
      for (const p of parseFeed(await googleFetch(feed, gapMs), source)) {
        const key = `${p.outlet}|${p.title}`;
        if (seen.has(key)) continue;
        seen.add(key);
        all.push(p);
      }
    } catch (e) {
      lastError = e;
    }
  }
  if (all.length === 0 && lastError) throw lastError;
  // 최신순으로 섞어 한 검색어가 목록을 독차지하지 않게
  return all.sort((a, b) => (b.publishedAt ?? 0) - (a.publishedAt ?? 0)).slice(0, source.limit ?? 120);
}
