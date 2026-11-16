// 진단: 청약·공공분양 정보 후보 주소가 클라우드에서 열리는지 확인. Actions > crawl > Run workflow 에서 diag를 켜면 실행됨
import iconv from 'iconv-lite';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';

async function probe(name, url, { method = 'GET', body, headers = {}, show = 700, find } = {}) {
  console.log(`\n### ${name}`);
  try {
    const jar = new Map();
    let cur = url; let res; let m = method; let b = body;
    for (let i = 0; i < 6; i++) {
      res = await fetch(cur, { method: m, body: b, headers: { 'User-Agent': UA, 'Accept-Language': 'ko-KR,ko;q=0.9', Cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; '), ...headers }, redirect: 'manual', signal: AbortSignal.timeout(20000) });
      for (const c of res.headers.getSetCookie?.() ?? []) { const kv = c.split(';')[0]; const j = kv.indexOf('='); if (j > 0) jar.set(kv.slice(0, j).trim(), kv.slice(j + 1)); }
      const loc = res.headers.get('location');
      if (res.status >= 300 && res.status < 400 && loc) { const next = new URL(loc, cur).toString(); if (next !== cur) { m = 'GET'; b = undefined; } cur = next; continue; }
      break;
    }
    const buf = Buffer.from(await res.arrayBuffer());
    const ctype = res.headers.get('content-type') || '';
    const cs = (ctype.match(/charset=([\w-]+)/i) || [])[1] || (buf.subarray(0, 2048).toString('latin1').match(/(?:charset|encoding)=["']?([\w-]+)/i) || [])[1] || 'utf-8';
    const text = iconv.encodingExists(cs) ? iconv.decode(buf, cs) : buf.toString('utf8');
    let out = text;
    if (find) { const i = text.indexOf(find); out = i >= 0 ? text.slice(Math.max(0, i - 500), i + show) : `('${find}' 없음) ` + text.slice(0, 300); }
    console.log(`${res.status} ${ctype} len=${buf.length} final=${cur}`);
    console.log(out.replace(/\s+/g, ' ').slice(0, show + 500));
    return text;
  } catch (e) { console.log('ERROR', e.message, e.cause?.code || e.cause?.message || ''); return null; }
}

const form = { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' };

// LH 청약플러스: 분양·임대 공고 목록
await probe('LH 청약플러스 공고 목록', 'https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancList.do?mi=1026', { find: 'wrtancInfoBtn', show: 1500 });
await probe('LH 청약플러스 분양주택 공고', 'https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancList.do?mi=1027', { find: '<tbody', show: 1800 });
// 청약홈: 분양정보 / 청약일정
await probe('청약홈 APT 분양정보', 'https://www.applyhome.co.kr/ai/aia/selectAPTLttotPblancListView.do', { find: '<tbody', show: 1800 });
await probe('청약홈 청약 캘린더', 'https://www.applyhome.co.kr/ai/aib/selectSubscrptCalenderView.do', { find: 'cal_', show: 1200 });
await probe('청약홈 캘린더 자료(POST)', 'https://www.applyhome.co.kr/ai/aib/selectSubscrptCalender.do', { method: 'POST', body: 'reqData=%7B%22inqirePd%22%3A%22202611%22%7D', headers: form, show: 1200 });
// 공공데이터포털 청약홈 분양정보 API (키 없이 어떤 응답이 오는지)
await probe('공공데이터 청약홈 분양정보 API(키 없음)', 'https://api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1/getAPTLttotPblancDetail?page=1&perPage=3', { show: 400 });
// SH 서울주택도시공사, GH 경기주택도시공사 공고
await probe('SH 주택분양 공고', 'https://www.i-sh.co.kr/main/lay2/program/S1T294C295/www/brd/m_241/list.do', { find: '<tbody', show: 1500 });
await probe('SH 첫 화면', 'https://www.i-sh.co.kr/main/index.do', { find: '공고', show: 800 });
await probe('GH 분양공고', 'https://www.gh.or.kr/gh/announcement-of-sale.do', { find: '<tbody', show: 1500 });
await probe('마이홈 공공분양 공고', 'https://www.myhome.go.kr/hws/portal/sch/selectLttotHouseListView.do', { find: '<tbody', show: 1500 });
