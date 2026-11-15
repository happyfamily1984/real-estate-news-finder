// 수집 소스 설정. kind: 'news'(언론) | 'gov'(정부·공공) | 'community'(커뮤니티)
//   feed     : RSS/Atom 주소 (fetch: fetchFeed)
//   filter   : true면 부동산 관련 제목만 남김 (경제 전체 피드, 타 부처 보도자료)
//   selectors: HTML 목록 파싱 (row/title/link/views/comments/likes/exclude)
//   keepParams : 글 주소에서 남길 쿼리 파라미터 (같은 글이 같은 id가 되도록)
//   disabled : true면 수집 안 함
import { fetchFeed, fetchGoogleNews } from './feeds.js';

const korea = (id, name, dept, color, filter = true) => ({
  id, name, board: '보도자료', kind: 'gov', color, filter,
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
  { id: 'hankyung', name: '한국경제', board: '부동산', kind: 'news', color: '#0a4da2', url: 'https://www.hankyung.com/realestate', feed: 'https://www.hankyung.com/feed/realestate', fetch: fetchFeed },
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
  korea('moef', '기획재정부', 'moef', '#5a4fcf'),
  korea('nts', '국세청', 'nts', '#b0542c'),
  { id: 'policy', name: '정책브리핑', board: '정책뉴스', kind: 'gov', color: '#2b7a9e', url: 'https://www.korea.kr/news/policyNewsList.do', feed: 'https://www.korea.kr/rss/policy.xml', fetch: fetchFeed, filter: true },

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
    id: 'ppomppu', name: '뽐뿌', board: '부동산포럼', kind: 'community', color: '#d9534f',
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
    id: 'clien', name: '클리앙', board: '내집마련당', kind: 'community', color: '#3d5a80',
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
