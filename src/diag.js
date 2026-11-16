// 진단: 청약홈 무순위·잔여세대 상세 페이지 주소 찾기. Actions > crawl > Run workflow 에서 diag를 켜면 실행됨
import { fetchHtml } from './fetcher.js';

const BASE = 'https://www.applyhome.co.kr/ai/aia/';
for (const list of ['selectAPTRemndrLttotPblancListView.do', 'selectAPTLttotPblancListView.do']) {
  console.log(`\n### ${list}`);
  const html = await fetchHtml(BASE + list, { cookies: true });
  const names = [...new Set(html.match(/[A-Za-z/]*[Dd]etail[A-Za-z]*\.do/g) || [])];
  console.log('상세 주소 후보:', names.join(', '));
  for (const m of html.matchAll(/.{260}[Dd]etail[A-Za-z]*\.do.{200}/gs)) console.log('  …', m[0].replace(/\s+/g, ' '));
  const row = html.match(/<tr[^>]*data-pbno[^>]*>/);
  console.log('첫 줄 속성:', row?.[0]);
  const scripts = [...html.matchAll(/<script[^>]*src="([^"]+)"/g)].map((m) => m[1]).filter((s) => /ai|aia|pblanc|lttot/i.test(s));
  console.log('스크립트:', scripts.join(', '));
  for (const src of scripts.slice(0, 4)) {
    try {
      const js = await fetchHtml(new URL(src, BASE).toString(), { cookies: true });
      for (const m of js.matchAll(/.{200}[Dd]etail[A-Za-z]*\.do.{160}/gs)) console.log(`  [${src.split('/').pop()}] …`, m[0].replace(/\s+/g, ' '));
    } catch (e) { console.log('  script ERROR', src, e.message); }
  }
  if (list.includes('Remndr') && row) {
    const pb = row[0].match(/data-pbno="(\d+)"/)?.[1]; const hm = row[0].match(/data-hmno="(\d+)"/)?.[1] || pb;
    for (const d of [...names, 'selectAPTRemndrLttotPblancDetail.do', 'selectAPTRemndrLttotPblancDetailView.do']) {
      const name = d.split('/').pop();
      for (const qs of [`houseManageNo=${hm}&pblancNo=${pb}`, `houseManageNo=${hm}&pblancNo=${pb}&gvPgmId=AIA02001`]) {
        try { const h = await fetchHtml(`${BASE}${name}?${qs}`, { cookies: true }); console.log(`  시도 ${name}?${qs} → 200 len ${h.length} 공급위치 ${h.includes('공급위치')}`); }
        catch (e) { console.log(`  시도 ${name}?${qs} → ${e.message}`); }
      }
    }
  }
}
