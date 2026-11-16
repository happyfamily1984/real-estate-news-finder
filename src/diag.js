// 진단: 청약홈 APT 분양정보 목록의 과거 공고 검색 방법 (분양가 자료 모으기용)
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';
import { parseApplyhomeDetail } from './apply.js';

const base = 'https://www.applyhome.co.kr/ai/aia/';
const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();
const html = await fetchHtml(base + 'selectAPTLttotPblancListView.do', { cookies: true, timeoutMs: 20000 });
const $ = cheerio.load(html);
$('form').each((_, f) => {
  console.log('\n## 폼', $(f).attr('id'), $(f).attr('name'), $(f).attr('action'), $(f).attr('method'));
  $(f).find('input,select').each((__, el) => {
    const e = $(el);
    const opts = el.tagName === 'select' ? e.find('option').map((___, o) => `${$(o).attr('value')}=${clean($(o).text())}`).get().slice(0, 30).join(',') : '';
    console.log('  ', el.tagName, e.attr('name'), e.attr('id'), e.attr('type'), JSON.stringify(e.attr('value') ?? ''), opts);
  });
});
console.log('\n## 쪽 나눔:', clean($('.paging, .pagination, .page_wrap, #paging').text()).slice(0, 300), '|', $('.paging a, .pagination a').map((_, a) => $(a).attr('href') || $(a).attr('onclick')).get().slice(0, 8).join(' , '));
console.log('## 총 건수 문구:', clean($('body').text()).match(/(전체|총)\s*[\d,]+\s*건/g));
const fnRe = new RegExp('function\\s+(fn\\w*(?:[Ss]earch|[Pp]age|[Ll]ist)\\w*|goPage|linkPage|search\\w*)\\s*\\([^)]*\\)\\s*\\{[\\s\\S]{0,700}', 'g');
for (const m of html.matchAll(fnRe)) console.log('\n## 함수:', clean(m[0]).slice(0, 700));
console.log('\n## 스크립트:', $('script[src]').map((_, s) => $(s).attr('src')).get().join(' , '));

// 검색 시도: GET/POST 여러 방식
async function tryFetch(label, url, body) {
  try {
    const res = await fetch(url, {
      method: body ? 'POST' : 'GET',
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36', 'Accept-Language': 'ko-KR,ko;q=0.9',
        ...(body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}), Referer: base + 'selectAPTLttotPblancListView.do',
      },
      body, signal: AbortSignal.timeout(25000),
    });
    const t = await res.text();
    const $$ = cheerio.load(t);
    const rows = $$('tbody tr[data-pbno]');
    const dates = rows.map((_, r) => $$(r).find('td').map((__, td) => clean($$(td).text())).get().find((c) => /^20\d{2}-\d{2}-\d{2}$/.test(c))).get();
    const total = clean($$('body').text()).match(/(전체|총)\s*[\d,]+\s*건/);
    console.log(`\n## ${label} → ${res.status} 길이 ${t.length} 줄 ${rows.length} 첫 ${dates[0]} 끝 ${dates.at(-1)} | 첫 단지 ${rows.first().attr('data-honm')} ${rows.first().attr('data-pbno')} | 총: ${total?.[0]} | 쪽: ${clean($$('.paging, .pagination').text()).slice(0, 80)}`);
    return rows.first().attr('data-pbno');
  } catch (e) { console.log(`\n## ${label} 실패 ${e.message}`); return null; }
}
const u = base + 'selectAPTLttotPblancListView.do';
await tryFetch('GET 기간 2021', `${u}?beginPd=202101&endPd=202112`);
await tryFetch('POST 기간 2021', u, 'beginPd=202101&endPd=202112&houseDetailSecd=01&suplyAreaCode=&houseNm=&chk0=&pageIndex=1&gvPgmId=AIA01M01');
await tryFetch('POST 기간 2021 2쪽', u, 'beginPd=202101&endPd=202112&houseDetailSecd=01&suplyAreaCode=&houseNm=&chk0=&pageIndex=2&gvPgmId=AIA01M01');
await tryFetch('POST 단지명', u, 'beginPd=202001&endPd=202612&houseNm=' + encodeURIComponent('과천') + '&pageIndex=1');
const old = await tryFetch('POST 2020', u, 'beginPd=202002&endPd=202006&pageIndex=1');
if (old) {
  const d = await fetchHtml(`${base}selectAPTLttotPblancDetail.do?houseManageNo=${old}&pblancNo=${old}`, { cookies: true, timeoutMs: 20000 });
  console.log('\n## 옛 공고 상세', old, JSON.stringify(parseApplyhomeDetail(d)));
  const $d = cheerio.load(d);
  $d('table').each((_, t) => {
    const cap = clean($d(t).find('caption').text());
    if (/공급금액|공급대상/.test(cap)) console.log('  표', cap, '|', $d(t).find('tr').slice(0, 6).map((__, tr) => $d(tr).find('th,td').map((___, c) => clean($d(c).text())).get().join(' / ')).get().join(' || '));
  });
}
