// 진단: R-ONE(한국부동산원) 통계표 찾기. Actions > crawl > Run workflow 에서 diag를 켜면 실행됨 (키는 로그에 찍지 않음)
const KEY = process.env.REB_API_KEY || '';
const BASE = 'https://www.reb.or.kr/r-one/openapi';
console.log('REB_API_KEY', KEY ? `있음 (길이 ${KEY.length})` : '없음');

async function call(api, params) {
  const qs = Object.entries({ KEY, Type: 'json', ...params }).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');
  const res = await fetch(`${BASE}/${api}.do?${qs}`, { signal: AbortSignal.timeout(40000) });
  const j = await res.json();
  const body = j[api];
  if (!body) return { total: 0, rows: [], raw: JSON.stringify(j).slice(0, 300) };
  return { total: body[0]?.head?.[0]?.list_total_count ?? 0, result: body[0]?.head?.[1]?.RESULT, rows: body[1]?.row ?? [] };
}

const tbl = await call('SttsApiTbl', { pIndex: 1, pSize: 1000 });
console.log('통계표', tbl.total, tbl.rows.length, JSON.stringify(tbl.result), tbl.raw || '');
const weekly = tbl.rows.filter((r) => r.DTACYCLE_CD === 'WK' || /\(주\)|주간/.test(r.STATBL_NM));
console.log('--- 주간 통계표');
for (const r of weekly) console.log(`${r.STATBL_ID} | ${r.STATBL_NM} | ${r.DTACYCLE_CD} | ${r.DATA_START_YY}~${r.DATA_END_YY}`);
console.log('--- 월간 중 수급·거래·전망·지가');
for (const r of tbl.rows.filter((r) => r.DTACYCLE_CD === 'MM' && /수급|거래현황|지가변동|실거래가격지수|전월세전환|매매가격지수_아파트|전세가격지수_아파트/.test(r.STATBL_NM)).slice(0, 40)) console.log(`${r.STATBL_ID} | ${r.STATBL_NM} | ${r.DTACYCLE_CD}`);

// 주간 아파트 매매·전세 지수/변동률, 수급동향 후보를 자세히
const picks = weekly.filter((r) => /아파트/.test(r.STATBL_NM) && /매매가격지수|전세가격지수|변동률|수급/.test(r.STATBL_NM)).slice(0, 8);
for (const r of picks.length ? picks : weekly.slice(0, 6)) {
  console.log(`\n### ${r.STATBL_ID} ${r.STATBL_NM}`);
  try {
    const itm = await call('SttsApiTblItm', { STATBL_ID: r.STATBL_ID, pIndex: 1, pSize: 300 });
    const items = itm.rows.filter((x) => /ITM|항목/.test(`${x.ITM_TAG}`) || x.ITM_ID);
    console.log('항목 키', Object.keys(itm.rows[0] || {}).join(','));
    console.log('항목 예', itm.rows.slice(0, 40).map((x) => `${x.ITM_TAG ?? ''}:${x.ITM_ID ?? x.CLS_ID}:${x.ITM_NM ?? x.CLS_NM}:${x.PAR_ITM_ID ?? ''}`).join(' / ').slice(0, 1500));
    const head = await call('SttsApiTblData', { STATBL_ID: r.STATBL_ID, DTACYCLE_CD: 'WK', pIndex: 1, pSize: 1 });
    const pages = Math.max(1, Math.ceil(head.total / 500));
    const last = await call('SttsApiTblData', { STATBL_ID: r.STATBL_ID, DTACYCLE_CD: 'WK', pIndex: pages, pSize: 500 });
    const times = [...new Set(last.rows.map((x) => x.WRTTIME_IDTFR_ID))];
    console.log('총', head.total, '마지막 쪽 행', last.rows.length, '시점', times.slice(-4).join(','), '| 설명', last.rows.at(-1)?.WRTTIME_DESC);
    const t = times.at(-1);
    const latest = last.rows.filter((x) => x.WRTTIME_IDTFR_ID === t);
    console.log('최신 시점 행', latest.length, '항목', [...new Set(latest.map((x) => `${x.ITM_ID}:${x.ITM_NM}`))].join(','));
    console.log(latest.slice(0, 30).map((x) => `${x.CLS_ID}:${x.CLS_FULLNM || x.CLS_NM}=${x.DTA_VAL}${x.UI_NM || ''}`).join(' / ').slice(0, 1800));
    // 시점 조건으로 직접 조회가 되는지
    const byTime = await call('SttsApiTblData', { STATBL_ID: r.STATBL_ID, DTACYCLE_CD: 'WK', WRTTIME_IDTFR_ID: t, pIndex: 1, pSize: 500 });
    console.log('시점 지정 조회', byTime.total, byTime.rows.length);
  } catch (e) { console.log('ERROR', e.message); }
}
