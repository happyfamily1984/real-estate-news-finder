// 진단: 후보 주소가 클라우드(GitHub Actions)에서 열리는지 확인. Actions > crawl > Run workflow 에서 diag를 켜면 실행됨
import iconv from 'iconv-lite';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const MUA = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

async function probe(name, url, { ua = UA, headers = {}, show = 400, find } = {}) {
  try {
    const res = await fetch(url, { headers: { 'User-Agent': ua, 'Accept-Language': 'ko-KR,ko;q=0.9', ...headers }, signal: AbortSignal.timeout(15000), redirect: 'follow' });
    const buf = Buffer.from(await res.arrayBuffer());
    const ctype = res.headers.get('content-type') || '';
    let cs = (ctype.match(/charset=([\w-]+)/i) || [])[1] || (buf.subarray(0, 2048).toString('latin1').match(/charset=["']?([\w-]+)/i) || [])[1] || 'utf-8';
    const text = iconv.encodingExists(cs) ? iconv.decode(buf, cs) : buf.toString('utf8');
    let body = text;
    if (find) { const i = text.indexOf(find); body = i >= 0 ? text.slice(Math.max(0, i - 200), i + show) : `('${find}' 없음) ` + text.slice(0, 200); }
    console.log(`\n### ${name}\n${res.status} ${ctype} len=${buf.length} final=${res.url}\n${body.replace(/\s+/g, ' ').slice(0, show + 200)}`);
    return text;
  } catch (e) {
    console.log(`\n### ${name}\nERROR ${e.message} / ${e.cause?.code || e.cause?.message || ''}`);
    return null;
  }
}

const q = (o) => Object.entries(o).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
const KB = 'https://data-api.kbland.kr/bfmstat/weekMnthlyHuseTrnd';

// 쿠키를 받아 다시 요청해야 열리는 사이트용 (국토부: 쿠키 없이 가면 자기 자신으로 계속 넘김)
async function cookieFetch(url, { ua = UA, max = 6 } = {}) {
  const jar = new Map();
  let cur = url;
  for (let i = 0; i < max; i++) {
    const res = await fetch(cur, { headers: { 'User-Agent': ua, 'Accept-Language': 'ko-KR,ko;q=0.9', Cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') }, redirect: 'manual', signal: AbortSignal.timeout(15000) });
    for (const c of res.headers.getSetCookie?.() ?? []) { const [kv] = c.split(';'); const j = kv.indexOf('='); jar.set(kv.slice(0, j).trim(), kv.slice(j + 1)); }
    console.log(`  [${i}] ${res.status} ${cur} loc=${res.headers.get('location') || ''} cookies=${[...jar.keys()].join(',')}`);
    if (res.status >= 300 && res.status < 400 && res.headers.get('location')) { cur = new URL(res.headers.get('location'), cur).toString(); continue; }
    const buf = Buffer.from(await res.arrayBuffer());
    const cs = ((res.headers.get('content-type') || '').match(/charset=([\w-]+)/i) || [])[1] || (buf.subarray(0, 2048).toString('latin1').match(/(?:charset|encoding)=["']?([\w-]+)/i) || [])[1] || 'utf-8';
    return { status: res.status, text: iconv.encodingExists(cs) ? iconv.decode(buf, cs) : buf.toString('utf8') };
  }
  throw new Error('redirect loop');
}
async function probeCookie(name, url, show = 700, find) {
  console.log(`\n### ${name}`);
  try {
    const r = await cookieFetch(url);
    let t = r.text; if (find) { const i = t.indexOf(find); t = i >= 0 ? t.slice(Math.max(0, i - 300), i + show) : `('${find}' 없음) ` + t.slice(0, 300); }
    console.log(r.status, 'len', r.text.length, t.replace(/\s+/g, ' ').slice(0, show + 300));
  } catch (e) { console.log('ERROR', e.message, e.cause?.code || e.cause?.message || ''); }
}
async function json(name, url, opt = {}) {
  console.log(`\n### ${name}`);
  try {
    const res = await fetch(url, { headers: { 'User-Agent': opt.ua || UA }, signal: AbortSignal.timeout(45000) });
    const j = await res.json();
    console.log(res.status);
    return j;
  } catch (e) { console.log('ERROR', e.message); return null; }
}
const cut = (o, n = 700) => JSON.stringify(o).slice(0, n);

// KB
for (const [name, path, params] of [
  ['KB 주간 매수우위지수', 'maktTrnd', { 메뉴코드: '01', 월간주간구분코드: '02' }],
  ['KB 주간 전세수급지수', 'maktTrnd', { 메뉴코드: '03', 월간주간구분코드: '02' }],
  ['KB 주간 아파트 전세 증감률', 'prcIndxInxrdcRt', { 월간주간구분코드: '02', 매물종별구분: '01', 매매전세코드: '02' }],
  ['KB 월간 매매가격전망지수', 'maktTrnd', { 메뉴코드: '05', 월간주간구분코드: '01' }],
]) {
  const j = await json(name, `${KB}/${path}?${q(params)}`);
  const d = j?.dataBody?.data;
  if (d) {
    console.log('업데이트', d.업데이트일자, '날짜 끝', (d.날짜리스트 || []).slice(-2), '지역', (d.데이터리스트 || []).map((r) => r.지역명).join(','));
    for (const r of (d.데이터리스트 || []).slice(0, 2)) console.log(cut({ ...r, dataList: (r.dataList || []).slice(-2) }, 500));
  } else console.log(cut(j, 300));
}
{
  const j = await json('KB PIR 구조', `${KB}/pir?${q({ 메뉴코드: '01' })}`);
  const d = j?.dataBody?.data;
  if (d) {
    console.log('날짜 끝', d.날짜리스트.slice(-2));
    console.log(cut(d.데이터리스트.map((r) => ({ ...r, 주택분위구분: undefined, keys: Object.keys(r), 분위: (r.주택분위구분 || []).map((h) => ({ 주택분위: h.주택분위, 소득: (h.소득분위구분 || []).map((s) => ({ ...s, dataList: (s.dataList || []).slice(-1) })) })) })), 1500));
  }
}

// 네이버 카페 부동산스터디 (clubid 12730407)
{
  const j = await json('네이버 카페 인기글 V3', 'https://apis.naver.com/cafe-web/cafe2/WeeklyPopularArticleListV3.json?cafeId=12730407&mobileWeb=true&adUnit=PC_CAFE_BOARD&ad=false', { ua: MUA });
  const r = j?.message?.result;
  console.log('result keys', r && Object.keys(r), 'error', cut(j?.message?.error, 200));
  const list = r?.popularArticleList || r?.articleList || [];
  console.log('count', list.length); console.log(cut(list[0], 1500));
  console.log(list.slice(0, 8).map((a) => `${a.subject} | r${a.readCount} c${a.commentCount} l${a.likeItCount ?? a.likeCount}`).join('\n'));
}
{
  const j = await json('네이버 카페 최신글', 'https://apis.naver.com/cafe-web/cafe2/ArticleListV2dot1.json?search.clubid=12730407&search.queryType=lastArticle&search.page=1&search.perPage=5&ad=false', { ua: MUA });
  const r = j?.message?.result;
  console.log('cafeName', r?.cafeName, 'count', r?.articleList?.length);
  const a = r?.articleList?.[0];
  if (a) console.log(cut(Object.fromEntries(Object.entries(a).filter(([, v]) => v !== false && v !== '' && v !== 0 && v !== null)), 900));
}

// 국토부 (쿠키)
await probeCookie('국토부 RSS', 'https://www.molit.go.kr/dev/board/board_rss.jsp?rss_id=NEWS', 900);
await probeCookie('국토부 보도자료 목록', 'https://www.molit.go.kr/USR/NEWS/m_71/lst.jsp', 900, 'dtl.jsp');

// R-ONE: 키 없이 통계표 목록·주간 자료가 얼마나 나오는지
for (const [name, url] of [
  ['R-ONE 통계표 목록(주간)', 'https://www.reb.or.kr/r-one/openapi/SttsApiTbl.do?Type=json&pIndex=1&pSize=300'],
]) {
  const j = await json(name, url);
  const rows = j?.SttsApiTbl?.[1]?.row || [];
  console.log('head', cut(j?.SttsApiTbl?.[0], 200), 'rows', rows.length);
  console.log(rows.filter((r) => /주\)|주간|WK/.test(`${r.STATBL_NM} ${r.DTACYCLE_CD}`)).map((r) => `${r.STATBL_ID}|${r.STATBL_NM}|${r.DTACYCLE_CD}`).join('\n').slice(0, 2500));
  if (rows[0]) console.log('row keys', Object.keys(rows[0]).join(','));
}
