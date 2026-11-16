// 물가 비교용 외식비: 한국소비자원 참가격 외식비(서울, 2009년부터 월별)에서 해마다 같은 달 값을 모은다.
// 품목: 냉면·비빔밥·김치찌개백반·삼겹살·자장면·삼계탕·칼국수·김밥.
// 앱의 '그때와 지금' 글에서 "짜장면이 N원 → M원 오를 동안 이 집은 …"처럼 쓴다. 결과는 stats.json의 dineout에 넣음.
import * as cheerio from 'cheerio';

const URL_ = 'https://www.price.go.kr/tprice/portal/servicepriceinfo/dineoutprice/dineOutPriceList.do';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const FIRST_YEAR = 2009;
const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 외식비 표 → 그 지역의 품목별 값(원) { 자장면: 7654, 김밥: 3646, … }. 표가 없으면 null */
export function parseDineOut(html, region = '서울') {
  const $ = cheerio.load(html);
  let out = null;
  $('table').each((_, t) => {
    if (out != null) return;
    const head = $(t).find('tr').filter((__, tr) => /자장면/.test($(tr).text())).first();
    if (!head.length) return;
    const cols = head.children('th,td').map((__, c) => clean($(c).text())).get();
    if (!cols.includes('자장면')) return;
    $(t).find('tr').each((__, tr) => {
      const c = $(tr).children('th,td').map((___, x) => clean($(x).text())).get();
      if (out != null || !c[0]?.startsWith(region) || c.length !== cols.length) return;
      const row = {};
      cols.forEach((name, i) => {
        if (i === 0) return;
        const v = parseInt(String(c[i]).replace(/[^\d]/g, ''), 10);
        if (Number.isFinite(v) && v >= 500 && v <= 100000) row[name] = v;
      });
      if (Object.keys(row).length) out = row;
    });
  });
  return out;
}

async function fetchMonth(year, month) {
  const body = `searchOrExcel=&searchYear=${year}&searchMonth=${month}&searchArea=`;
  const res = await fetch(URL_, { method: 'POST', headers: { 'User-Agent': UA, 'Accept-Language': 'ko-KR,ko;q=0.9', 'Content-Type': 'application/x-www-form-urlencoded', Referer: URL_ }, body, signal: AbortSignal.timeout(25000) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return parseDineOut(await res.text());
}

/**
 * prev: { month: '10', latest: '2026-10', items: { 자장면: { 2009: 3773, … }, … }, updatedAt }
 * 가장 최근에 값이 있는 달을 찾고, 해마다 그 달의 값을 모음. 7일 안에 받은 것이 있으면 그대로 둠.
 */
export async function collectDineOut(prev, { now = Date.now(), fetchOne = fetchMonth } = {}) {
  if (prev?.updatedAt && now - prev.updatedAt < 7 * 86400_000 && Object.keys(prev.items ?? {}).length) return prev;
  const d = new Date(now + 9 * 3600_000);
  let y = d.getUTCFullYear(); let m = d.getUTCMonth() + 1;
  // 최근 값이 있는 달 (발표가 한두 달 늦음)
  let latest = null;
  for (let i = 0; i < 5 && latest == null; i++) {
    const mm = String(m).padStart(2, '0');
    const v = await fetchOne(y, mm).catch(() => null);
    if (v) latest = { y, mm, v };
    else { m--; if (m === 0) { m = 12; y--; } }
    await sleep(150);
  }
  if (!latest) throw new Error('최근 값을 찾지 못함');
  const items = {};
  const put = (year, row) => { for (const [name, v] of Object.entries(row)) (items[name] ??= {})[year] = v; };
  put(latest.y, latest.v);
  for (let year = latest.y - 1; year >= FIRST_YEAR; year--) {
    // 같은 달 값이 없으면(조사 시작 전 등) 그 해 12월 값
    const row = (await fetchOne(year, latest.mm).catch(() => null)) ?? (await fetchOne(year, '12').catch(() => null));
    if (row) put(year, row);
    await sleep(150);
  }
  return { month: latest.mm, latest: `${latest.y}-${latest.mm}`, items, source: '한국소비자원 참가격 외식비(서울)', updatedAt: now };
}
