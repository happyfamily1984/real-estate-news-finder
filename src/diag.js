// 진단: 짜장면 값 자료 (참가격 외식비) 접속과 구조
import * as cheerio from 'cheerio';

const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36';
async function get(url, body) {
  const res = await fetch(url, { method: body ? 'POST' : 'GET', headers: { 'User-Agent': UA, 'Accept-Language': 'ko-KR,ko;q=0.9', ...(body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}) }, body, signal: AbortSignal.timeout(25000) });
  return { status: res.status, text: await res.text(), type: res.headers.get('content-type') };
}
const urls = [
  'https://www.price.go.kr/tprice/portal/servicepriceinfo/dineoutprice/dineOutPriceList.do',
  'https://www.price.go.kr/tprice/portal/main/main.do',
  'https://www.price.go.kr/tprice/portal/dailynecessitypriceinfo/dineoutprice/dineOutPriceList.do',
];
for (const u of urls) {
  try {
    const r = await get(u);
    const $ = cheerio.load(r.text);
    console.log('\n##', u, '→', r.status, r.type, '길이', r.text.length, '| 제목', clean($('title').text()));
    console.log(' 자장면 언급:', (r.text.match(/자장면|짜장면/g) || []).length, '| 표 줄', $('tr').length);
    $('tr').filter((_, tr) => /자장면|짜장면/.test($(tr).text())).slice(0, 3).each((_, tr) => console.log(' 줄:', clean($(tr).text()).slice(0, 400)));
    $('thead tr, tr').slice(0, 3).each((_, tr) => console.log(' 머리:', clean($(tr).text()).slice(0, 300)));
    $('form').slice(0, 4).each((_, f) => console.log(' 폼', $(f).attr('id'), $(f).attr('action'), '|', $(f).find('input,select').map((__, e) => `${$(e).attr('name')}=${$(e).attr('value') ?? ''}${e.tagName === 'select' ? '[' + $(e).find('option').map((___, o) => $(o).attr('value')).get().slice(0, 8).join(',') + ']' : ''}`).get().slice(0, 25).join(' ')));
    console.log(' 외식 링크:', $('a').map((_, a) => `${clean($(a).text()).slice(0, 20)}→${$(a).attr('href')}`).get().filter((x) => /외식|서비스|가격/.test(x)).slice(0, 15).join(' , '));
  } catch (e) { console.log('\n##', u, '실패', e.message, e.cause?.code ?? ''); }
}
