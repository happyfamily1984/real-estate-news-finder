// 진단: 실거래 중계 서버 접속(토큰 필요 여부), R-ONE·KB 통계의 시군구 단위 제공 여부
const px = 'https://apt-alert.k-rebirth7-03a.workers.dev';
for (const u of [`${px}/health`, `${px}/molit/trade?LAWD_CD=41290&DEAL_YMD=202610&pageNo=1&numOfRows=5`, `${px}/molit/rent?LAWD_CD=41290&DEAL_YMD=202610&pageNo=1&numOfRows=5`]) {
  try {
    const r = await fetch(u, { signal: AbortSignal.timeout(30000) });
    const t = await r.text();
    console.log('\n##', u, '→', r.status, r.headers.get('x-cache'), t.slice(0, 900).replace(/\s+/g, ' '));
  } catch (e) { console.log('\n##', u, '실패', e.message); }
}

// R-ONE 주간 매매가격지수·매매수급지수의 지역 단계
const key = process.env.REB_API_KEY || '';
for (const stat of ['T244183132827305', 'T248163133074619']) {
  try {
    const call = async (p, s) => {
      const r = await fetch(`https://www.reb.or.kr/r-one/openapi/SttsApiTblData.do?KEY=${key}&Type=json&STATBL_ID=${stat}&DTACYCLE_CD=WK&pIndex=${p}&pSize=${s}`, { signal: AbortSignal.timeout(40000) });
      const b = (await r.json()).SttsApiTblData;
      return { total: b?.[0]?.head?.[0]?.list_total_count ?? 0, rows: b?.[1]?.row ?? [] };
    };
    const head = await call(1, 1);
    const last = Math.ceil(head.total / 1000);
    const rows = [...(await call(last - 1, 1000)).rows, ...(await call(last, 1000)).rows];
    const t = rows.at(-1).WRTTIME_IDTFR_ID;
    const cur = rows.filter((r) => r.WRTTIME_IDTFR_ID === t);
    console.log('\n## R-ONE', stat, '전체', head.total, '시점', t, '이 시점 줄 수', cur.length, '항목', [...new Set(cur.map((r) => r.ITM_NM))].join(','));
    console.log(' 첫 줄 키:', JSON.stringify(cur[0]));
    console.log(' 지역:', cur.map((r) => r.CLS_FULLNM || r.CLS_NM).join(' | '));
  } catch (e) { console.log('R-ONE 실패', stat, e.message); }
}
