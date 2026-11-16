// 진단: 참가격 외식비 표를 연·월로 조회하는 방법 (GET/POST)과 표의 줄 모양
import * as cheerio from 'cheerio';
import { parseDineOut } from './prices.js';

const U = 'https://www.price.go.kr/tprice/portal/servicepriceinfo/dineoutprice/dineOutPriceList.do';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36';
const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();
async function tryOne(label, url, body) {
  const t0 = Date.now();
  try {
    const res = await fetch(url, { method: body ? 'POST' : 'GET', headers: { 'User-Agent': UA, 'Accept-Language': 'ko-KR,ko;q=0.9', ...(body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}), Referer: U }, body, signal: AbortSignal.timeout(45000) });
    const text = await res.text();
    const $ = cheerio.load(text);
    const table = $('table').filter((_, t) => /자장면/.test($(t).text())).last();
    const rows = table.find('tr').map((_, tr) => $(tr).children('th,td').map((__, c) => clean($(c).text())).get().join(' | ')).get();
    const sel = (name) => $(`select[name=${name}] option[selected]`).attr('value');
    console.log(`\n## ${label} → ${res.status} ${Date.now() - t0}ms 길이 ${text.length} | 고른 연 ${sel('searchYear')} 월 ${sel('searchMonth')} | 표 ${$('table').length}개, 줄 ${rows.length} | 해석 ${JSON.stringify(parseDineOut(text))}`);
    rows.slice(0, 5).forEach((r) => console.log('   ', r.slice(0, 200)));
  } catch (e) { console.log(`\n## ${label} 실패 ${Date.now() - t0}ms ${e.message} ${e.cause?.code ?? ''}`); }
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
await tryOne('GET 기본', U);
await wait(4000);
await tryOne('GET 2016-10', `${U}?searchYear=2016&searchMonth=10&searchArea=`);
await wait(4000);
await tryOne('POST 2016-10', U, 'searchOrExcel=&searchYear=2016&searchMonth=10&searchArea=');
await wait(4000);
await tryOne('GET 2012-06 서울', `${U}?searchYear=2012&searchMonth=06&searchArea=020100000`);
