// 수집 소스 설정. kind: 'news'(언론) | 'gov'(정부·공공) | 'community'(커뮤니티)
//   feed     : RSS/Atom 주소 (fetch: fetchFeed)
//   filter   : true면 부동산 관련 제목만 남김 (경제 전체 피드, 타 부처 보도자료)
//   selectors: HTML 목록 파싱 (row/title/link/views/comments/likes/exclude)
//   keepParams : 글 주소에서 남길 쿼리 파라미터 (같은 글이 같은 id가 되도록)
//   disabled : true면 수집 안 함
import { fetchFeed, fetchGoogleNews } from './feeds.js';

// 정책브리핑 부처별 보도자료 RSS. 정부 사이트(korea.kr, molit.go.kr, fsc.go.kr)는 해외 클라우드 접속을 막아서(HTTP 404/503)
// 클라우드에서는 꺼두고, 앱이 휴대폰에서 직접 받아옴 (android GovLocal.kt). 테스트에서 피드 형식 확인용으로 정의는 남겨둠
const korea = (id, name, dept, color, filter = true) => ({
  id, name, board: '보도자료', kind: 'gov', color, filter, disabled: true,
  url: 'https://www.korea.kr/briefing/pressReleaseList.do',
  feed: `https://www.korea.kr/rss/dept_${dept}.xml`, fetch: fetchFeed,
});

export const SOURCES = [
  // ---- 언론 ----
  {
    id: 'gnews', name: '구글뉴스', board: '부동산 종합', kind: 'news', color: '#4285f4',
    url: 'https://news.google.com/', fetch: fetchGoogleNews, outletFromTitle: true, limit: 150,
    queries: ['부동산', '아파트 청약 분양', '재건축 재개발', '전세 월세', '주택담보대출 금리', '집값 실거래가'],
  },
  // 한국경제는 클라우드 접근을 막음(HTTP 403). 구글뉴스를 통해 들어오므로 꺼둠
  { id: 'hankyung', name: '한국경제', board: '부동산', kind: 'news', color: '#0a4da2', url: 'https://www.hankyung.com/realestate', feed: 'https://www.hankyung.com/feed/realestate', fetch: fetchFeed, disabled: true },
  { id: 'mk', name: '매일경제', board: '부동산', kind: 'news', color: '#f26522', url: 'https://www.mk.co.kr/news/realestate/', feed: 'https://www.mk.co.kr/rss/50300009/', fetch: fetchFeed },
  { id: 'yna', name: '연합뉴스', board: '경제', kind: 'news', color: '#00529b', url: 'https://www.yna.co.kr/economy/real-estate', feed: 'https://www.yna.co.kr/rss/economy.xml', fetch: fetchFeed, filter: true },
  { id: 'donga', name: '동아일보', board: '경제', kind: 'news', color: '#00857c', url: 'https://www.donga.com/news/Economy', feed: 'https://rss.donga.com/economy.xml', fetch: fetchFeed, filter: true },
  { id: 'khan', name: '경향신문', board: '경제', kind: 'news', color: '#1a6fb5', url: 'https://www.khan.co.kr/economy', feed: 'https://www.khan.co.kr/rss/rssdata/economy_news.xml', fetch: fetchFeed, filter: true },
  { id: 'hani', name: '한겨레', board: '경제', kind: 'news', color: '#2e8b7d', url: 'https://www.hani.co.kr/arti/economy', feed: 'https://www.hani.co.kr/rss/economy/', fetch: fetchFeed, filter: true },
  { id: 'asiae', name: '아시아경제', board: '부동산', kind: 'news', color: '#c8102e', url: 'https://www.asiae.co.kr/realestate', feed: 'https://www.asiae.co.kr/rss/realestate.htm', fetch: fetchFeed },
  { id: 'sedaily', name: '서울경제', board: '부동산', kind: 'news', color: '#7a3e9d', url: 'https://www.sedaily.com/v/NewsMain/GB', feed: 'https://www.sedaily.com/rss/realestate', fetch: fetchFeed },

  // ---- 정부·공공 (정책브리핑 부처별 보도자료 RSS) ----
  korea('molit', '국토교통부', 'molit', '#1f6f43', false),
  korea('fsc', '금융위원회', 'fsc', '#8a6d1d'),
  // 구글 뉴스에서 정부 사이트 글만 검색 (클라우드에서 받을 수 있는 정부 발표)
  {
    id: 'gov-search', name: '정부 발표 검색', board: '정부 사이트', kind: 'gov', color: '#3f7d5c',
    url: 'https://news.google.com/', fetch: fetchGoogleNews, outletFromTitle: true, limit: 40, windowDays: 3,
    queries: ['site:korea.kr 주택', 'site:korea.kr 부동산', 'site:korea.kr 전세 OR 청약 OR 대출'],
  },

  // ---- 커뮤니티 ----
  {
    id: 'dcinside', name: '디시 부동산갤', board: '개념글', kind: 'community', color: '#3b4890',
    url: 'https://gall.dcinside.com/board/lists/?id=immovables&exception_mode=recommend',
    keepParams: ['id', 'no'],
    selectors: {
      row: 'tr.ub-content.us-post',
      exclude: '[data-type="icon_notice"]',
      title: 'td.gall_tit > a:first-child',
      views: 'td.gall_count',
      likes: 'td.gall_recommend',
      comments: 'td.gall_tit .reply_num',
    },
  },
  {
    // 클라우드 접근을 막음(HTTP 403) → 꺼둠
    id: 'ppomppu', name: '뽐뿌', board: '부동산포럼', kind: 'community', color: '#d9534f', disabled: true,
    url: 'https://www.ppomppu.co.kr/zboard/zboard.php?id=house',
    keepParams: ['id', 'no'],
    selectors: {
      row: 'tr.baseList',
      exclude: '.baseNotice',
      title: 'a.baseList-title',
      views: 'td.baseList-views',
      likes: 'td.baseList-rec',
      comments: '.baseList-c',
    },
  },
  {
    // 게시판 주소 확인 필요(HTTP 404) → 꺼둠
    id: 'clien', name: '클리앙', board: '내집마련당', kind: 'community', color: '#3d5a80', disabled: true,
    url: 'https://www.clien.net/service/board/cm_house',
    selectors: {
      row: 'div.list_item.symph_row',
      exclude: '.blocked, .notice',
      title: 'span.subject_fixed',
      link: 'a.list_subject',
      views: '.list_hit .hit',
      comments: '.list_reply .rSymph05',
      likes: '.list_symph span',
    },
  },
];

export const ACTIVE_SOURCES = SOURCES.filter((s) => !s.disabled);
export const sourceById = Object.fromEntries(SOURCES.map((s) => [s.id, s]));
