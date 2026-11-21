// 진단용. 조사할 때마다 다시 씀 (crawl.yml을 diag: true로 실행하면 돎)
// 이번: 법원경매정보(courtauction.go.kr) 물건 검색이 수집기에서 되는지, 응답 꼴이 어떤지
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const BASE = 'https://www.courtauction.go.kr';
const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, '');
const now = new Date(Date.now() + 9 * 3600e3);
const jar = new Map();
const keep = (res) => { for (const c of res.headers.getSetCookie?.() ?? []) { const kv = c.split(';')[0]; const j = kv.indexOf('='); if (j > 0) jar.set(kv.slice(0, j).trim(), kv.slice(j + 1)); } };
const cookie = () => [...jar].map(([k, v]) => `${k}=${v}`).join('; ');
async function show(label, url, init = {}) {
  try {
    const res = await fetch(url, { ...init, headers: { 'User-Agent': UA, 'Accept-Language': 'ko-KR,ko;q=0.9', Cookie: cookie(), ...(init.headers || {}) }, signal: AbortSignal.timeout(25000) });
    keep(res);
    const body = await res.text();
    console.log(`\n== ${label} [${res.status}] type=${res.headers.get('content-type')} len=${body.length}`);
    return body;
  } catch (e) { console.log(`\n== ${label} [실패] ${e.message} ${e.cause?.code || ''}`); return ''; }
}
const home = await show('첫 화면', `${BASE}/pgj/index.on`);
console.log(home.slice(0, 300).replace(/\s+/g, ' '));
const search = (extra, pageSize = 40) => ({
  dma_pageInfo: { pageNo: 1, pageSize, bfPageNo: '', startRowNo: '', totalCnt: '', totalYn: 'Y', groupTotalCount: '' },
  dma_srchGdsDtlSrchInfo: {
    rletDspslSpcCondCd: '', bidDvsCd: '000331', mvprpRletDvsCd: '00031R', cortAuctnSrchCondCd: '0004601',
    rprsAdongSdCd: '41', rprsAdongSggCd: '173', rprsAdongEmdCd: '', rdnmSdCd: '', rdnmSggCd: '', rdnmNo: '',
    mvprpDspslPlcAdongSdCd: '', mvprpDspslPlcAdongSggCd: '', mvprpDspslPlcAdongEmdCd: '', rdDspslPlcAdongSdCd: '', rdDspslPlcAdongSggCd: '', rdDspslPlcAdongEmdCd: '',
    cortOfcCd: '', jdbnCd: '', execrOfcDvsCd: '', lclDspslGdsLstUsgCd: '', mclDspslGdsLstUsgCd: '', sclDspslGdsLstUsgCd: '', cortAuctnMbrsId: '',
    aeeEvlAmtMin: '', aeeEvlAmtMax: '', lwsDspslPrcRateMin: '', lwsDspslPrcRateMax: '', flbdNcntMin: '', flbdNcntMax: '', objctArDtsMin: '', objctArDtsMax: '',
    mvprpArtclKndCd: '', mvprpArtclNm: '', mvprpAtchmPlcTypCd: '', notifyLoc: 'off', lafjOrderBy: '', pgmId: 'PGJ151F01', csNo: '', cortStDvs: '1', statNum: 1,
    bidBgngYmd: ymd(now), bidEndYmd: ymd(new Date(now.getTime() + 90 * 86400e3)), dspslDxdyYmd: '', fstDspslHh: '', scndDspslHh: '', thrdDspslHh: '', fothDspslHh: '',
    dspslPlcNm: '', lwsDspslPrcMin: '', lwsDspslPrcMax: '', grbxTypCd: '', gdsVendNm: '', fuelKndCd: '', carMdyrMax: '', carMdyrMin: '', carMdlNm: '', sideDvsCd: '', ...extra,
  },
});
const post = (label, path, body) => show(label, `${BASE}${path}`, {
  method: 'POST', body: JSON.stringify(body),
  headers: { 'Content-Type': 'application/json;charset=UTF-8', Accept: 'application/json', Referer: `${BASE}/pgj/index.on?w2xPath=/pgj/ui/pgj100/PGJ151F00.xml`, Origin: BASE, 'SC-Userid': 'NONUSER', submissionid: 'mf_wfm_mainFrame_sbm_selectGdsDtlSrch' },
});

const all = [];
let info = null, first = null;
for (let page = 1; page <= 15; page++) {
  const b = search({ cortOfcCd: 'B000254', rprsAdongSdCd: '', rprsAdongSggCd: '' }, 100);
  b.dma_pageInfo.pageNo = page;
  const txt = await post(`안양지원 ${page}쪽`, '/pgj/pgjsearch/searchControllerMain.on', b);
  let j; try { j = JSON.parse(txt); } catch { console.log('JSON 아님:', txt.slice(0, 800)); break; }
  const list = j.data?.dlt_srchResult || [];
  if (page === 1) { info = j.data?.dma_pageInfo; first = list[0]; console.log('data 키:', Object.keys(j.data || {}), 'message:', j.message, 'pageInfo:', JSON.stringify(info)); }
  all.push(...list);
  if (list.length < 100) break;
  await new Promise((r) => setTimeout(r, 1200));
}
console.log('모은 건수:', all.length, '법원:', [...new Set(all.map((r) => r.jiwonNm))].join(','));
console.log('용도별:', JSON.stringify(Object.fromEntries(Object.entries(Object.groupBy(all, (r) => r.dspslUsgNm)).map(([k, v]) => [k, v.length]))));
if (first) console.log('첫 물건 전체:', JSON.stringify(first));
const hit = all.filter((r) => /한가람|공작|아크로타워|힐스테이트|더퍼스트/.test(`${r.buldNm} ${r.printSt}`));
console.log('6개 단지 낱말에 걸린 물건:', hit.length);
for (const r of hit) console.log(' *', JSON.stringify(Object.fromEntries(Object.entries(r).filter(([, v]) => v !== '' && v != null))));
const gy = all.filter((r) => /관양동|평촌동|호계동|비산동/.test(r.printSt) && /아파트|오피스텔/.test(r.dspslUsgNm)).slice(0, 6);
for (const r of gy) console.log(' ·', r.srnSaNo, r.dspslUsgNm, r.buldNm, '|', r.printSt, '|', r.gamevalAmt, r.minmaePrice, r.yuchalCnt, r.maeGiil);
const t = hit[0] || gy[0] || first;
if (t) {
  for (const [label, path, body] of [
    ['상세A', '/pgj/pgj15B/selectAuctnCsSrchRslt.on', { dma_srchGdsDtlSrch: { csNo: t.saNo, cortOfcCd: t.boCd, dspslGdsSeq: t.maemulSer, pgmId: 'PGJ15BA00', srchInfo: {} } }],
    ['상세B', '/pgj/pgj15B/selectAuctnCsSrchRslt.on', { dma_srchGdsDtlSrch: { csNo: t.saNo, cortOfcCd: t.boCd, dspslGdsSeq: String(t.maemulSer), pgmId: 'PGJ15BA00' } }],
  ]) {
    const d = await post(label, path, body);
    try { const j = JSON.parse(d); console.log(label, 'message:', j.message, 'data 키:', Object.keys(j.data || {})); for (const [k, v] of Object.entries(j.data?.dma_result || j.data || {})) console.log('   ', k, '=>', JSON.stringify(v).slice(0, 700)); } catch { console.log(label, d.slice(0, 600)); }
    await new Promise((r) => setTimeout(r, 1200));
  }
}
process.exit(1); // 진단만 하고 멈춤 (수집 로그가 섞이지 않게)
