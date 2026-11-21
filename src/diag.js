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
const txt = await post('물건 검색(안양 동안구, 90일)', '/pgj/pgjsearch/searchControllerMain.on', search({}));
try {
  const j = JSON.parse(txt);
  console.log('최상위 키:', Object.keys(j), '/ data 키:', Object.keys(j.data || {}));
  console.log('pageInfo:', JSON.stringify(j.data?.dma_pageInfo));
  const list = j.data?.dlt_srchResult || [];
  console.log('건수:', list.length);
  if (list[0]) console.log('첫 물건 전체:', JSON.stringify(list[0]));
  for (const r of list) console.log(' -', Object.entries(r).filter(([k, v]) => v && /SaNo|printSt|buldNm|Amt|Price|Giil|yuchal|usg|Usg|jiwonNm|bldNm|Nm$/.test(k)).map(([k, v]) => `${k}=${String(v).slice(0, 60)}`).join(' | '));
} catch (e) { console.log('JSON 아님:', txt.slice(0, 1500).replace(/\s+/g, ' ')); }
