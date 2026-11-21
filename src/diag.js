// 진단용. 조사할 때마다 다시 씀 (crawl.yml을 diag: true로 실행하면 돎)
// 이번: 네이버 부동산 매물 주소가 수집기(깃허브)에서 열리는지 확인
const UA = 'Mozilla/5.0 (Linux; Android 14; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';
const urls = [
  'https://m.land.naver.com/complex/getComplexArticleList?hscpNo=8928&tradTpCd=A1&order=prc&showR0=N&page=1',
  'https://m.land.naver.com/complex/ajax/complexListByCortarNo?cortarNo=1168010600',
  'https://m.land.naver.com/cluster/ajax/articleList?rletTpCd=APT&tradTpCd=A1&z=14&lat=37.4979&lon=127.0621&btm=37.48&lft=127.04&top=37.51&rgt=127.08&cortarNo=1168010600&page=1',
  'https://m.land.naver.com/map/getRegionList?cortarNo=1168000000&mycortarNo=',
  'https://m.land.naver.com/search/result/' + encodeURIComponent('은마아파트'),
  'https://m.land.naver.com/complex/info/8928',
  'https://new.land.naver.com/api/regions/complexes?cortarNo=1168010600&realEstateType=APT&order=',
  'https://new.land.naver.com/api/articles/complex/8928?realEstateType=APT&tradeType=A1&page=1&order=rank',
  'https://fin.land.naver.com/front-api/v1/complex?complexNumber=8928',
];
for (const u of urls) {
  try {
    const res = await fetch(u, {
      headers: { 'User-Agent': UA, Referer: 'https://m.land.naver.com/', 'Accept-Language': 'ko-KR,ko;q=0.9', Accept: 'application/json, text/plain, */*' },
      redirect: 'manual', signal: AbortSignal.timeout(20000),
    });
    const body = await res.text();
    console.log(`\n[${res.status}] ${u}\n  location=${res.headers.get('location') || ''} type=${res.headers.get('content-type')} len=${body.length}\n  ${body.slice(0, 900).replace(/\s+/g, ' ')}`);
  } catch (e) { console.log(`\n[실패] ${u} ${e.message} ${e.cause?.code || ''}`); }
  await new Promise((r) => setTimeout(r, 1500));
}
