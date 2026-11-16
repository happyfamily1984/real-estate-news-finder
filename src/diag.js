// 진단: 실거래 중계 서버 접속(토큰 필요 여부), R-ONE·KB 통계의 시군구 단위 제공 여부
const px = 'https://apt-alert.k-rebirth7-03a.workers.dev';
for (const u of [`${px}/health`, `${px}/molit/trade?LAWD_CD=41290&DEAL_YMD=202610&pageNo=1&numOfRows=5`, `${px}/molit/rent?LAWD_CD=41290&DEAL_YMD=202610&pageNo=1&numOfRows=5`]) {
  try {
    const r = await fetch(u, { signal: AbortSignal.timeout(30000) });
    const t = await r.text();
    console.log('\n##', u, '→', r.status, r.headers.get('x-cache'), t.slice(0, 900).replace(/\s+/g, ' '));
  } catch (e) { console.log('\n##', u, '실패', e.message); }
}
