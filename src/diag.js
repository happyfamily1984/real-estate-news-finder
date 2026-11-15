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

for (const [name, path, params] of [
  ['KB 주간 아파트 매매 증감률', 'prcIndxInxrdcRt', { 월간주간구분코드: '02', 매물종별구분: '01', 매매전세코드: '01' }],
  ['KB 주간 매수우위지수', 'maktTrnd', { 메뉴코드: '01', 월간주간구분코드: '02' }],
  ['KB PIR', 'pir', { 메뉴코드: '01' }],
]) {
  const t = await probe(name, `${KB}/${path}?${q(params)}`, { show: 300 });
  try {
    const d = JSON.parse(t).dataBody.data;
    console.log('keys:', Object.keys(d), '| 날짜 끝:', (d.날짜리스트 || []).slice(-3), '| 지역 수:', (d.데이터리스트 || []).length);
    for (const r of (d.데이터리스트 || []).slice(0, 4)) console.log(JSON.stringify({ ...r, dataList: (r.dataList || []).slice(-2) }).slice(0, 400));
    console.log('지역명:', (d.데이터리스트 || []).map((r) => r.지역명).join(','));
  } catch (e) { console.log('parse fail', e.message); }
}

await probe('뽐뿌 모바일 목록', 'https://m.ppomppu.co.kr/new/bbs_list.php?id=house', { ua: MUA, find: 'bbs_view.php', show: 900 });
await probe('뽐뿌 RSS', 'https://www.ppomppu.co.kr/rss.php?id=house', { show: 500 });
await probe('네이버 카페 부동산스터디 목록 API', 'https://apis.naver.com/cafe-web/cafe2/ArticleListV2dot1.json?search.clubid=10322296&search.queryType=lastArticle&search.page=1&search.perPage=20&ad=false', { ua: MUA, show: 900 });
await probe('네이버 카페 주소 확인', 'https://cafe.naver.com/jaegebal', { find: 'clubid', show: 200 });
await probe('국토부 RSS https', 'https://www.molit.go.kr/dev/board/board_rss.jsp?rss_id=NEWS', { show: 500 });
await probe('국토부 RSS http', 'http://www.molit.go.kr/dev/board/board_rss.jsp?rss_id=NEWS', { show: 500 });
await probe('국토부 보도자료 목록', 'https://www.molit.go.kr/USR/NEWS/m_71/lst.jsp', { find: 'dtl.jsp', show: 500 });
await probe('서울시 주택 새소식 피드', 'https://news.seoul.go.kr/citybuild/feed', { show: 600 });
await probe('서울시 보도자료', 'https://www.seoul.go.kr/news/news_report.do', { find: 'news_report', show: 400 });
await probe('경기도 보도자료 RSS', 'https://gnews.gg.go.kr/rss/gnewsRssBodo.do', { show: 600 });
await probe('R-ONE API (키 없이)', 'https://www.reb.or.kr/r-one/openapi/SttsApiTblData.do?Type=json&STATBL_ID=A_2024_00045&DTACYCLE_CD=MM', { show: 300 });
