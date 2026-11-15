// 데모 모드 (DEMO=1): 실제 사이트에 접속하지 않고 가짜 글을 만들어
// 화면과 알림 동작을 확인할 수 있게 합니다. 모든 글 제목에 [데모]가 붙습니다.

const TITLES = [
  '서울 아파트값 3주 연속 상승… 강남 신고가 잇따라', '국토부, 수도권 주택 공급 확대 대책 발표', '한은 기준금리 동결… 주담대 금리는 소폭 하락',
  '과천 무순위 청약에 10만명 몰려', '1기 신도시 선도지구 재건축 일정 확정', '전세사기 피해 지원 특별법 개정안 국회 통과',
  '양도세 비과세 요건 달라진다… 내년 시행', '수도권 전셋값 상승폭 확대, 월세 전환 가속', '스트레스 DSR 3단계 시행… 대출 한도 얼마나 줄어드나',
  '분양가 상한제 아파트 청약 일정 총정리', '토지거래허가구역 확대 지정, 거래량 급감', '재개발 조합 시공사 선정 경쟁 치열',
  '지방 미분양 주택 다시 증가세', '디딤돌 대출 금리 인상, 실수요자 부담', '오피스텔 월세 역대 최고가 경신', '공시가격 현실화 계획 수정안 발표',
];
const OUTLETS = ['한국경제', '매일경제', '연합뉴스', '머니투데이', '조선비즈', '이데일리'];

const state = new Map(); // source id -> posts
let counter = 1;
const pick = (a) => a[Math.floor(Math.random() * a.length)];

function makePost(source, n) {
  const p = {
    id: `${source.id}:demo-${n}`,
    source: source.id,
    title: `[데모] ${pick(TITLES)}`,
    url: `${new URL(source.url).origin}/?demo=${n}`,
    views: null, comments: null, likes: null,
  };
  if (source.kind === 'community') {
    p.views = Math.floor(Math.random() * 20000) + 500;
    p.comments = Math.floor(Math.random() * 300);
    p.likes = Math.floor(Math.random() * 500);
  } else {
    p.publishedAt = Date.now() - Math.floor(Math.random() * 20 * 3600_000);
    p.summary = '데모용 요약 문장입니다. 실제 수집에서는 기사 앞부분이 들어갑니다.';
    if (source.outletFromTitle) p.outlet = pick(OUTLETS);
  }
  return p;
}

export async function demoFetchItems(source) {
  let posts = state.get(source.id);
  if (!posts) posts = Array.from({ length: 12 }, () => makePost(source, counter++));
  else if (Math.random() < 0.5) posts = [makePost(source, counter++), ...posts.slice(0, 11)];
  state.set(source.id, posts);
  return posts;
}
