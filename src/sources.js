// 수집 소스 설정. kind: 'news'(언론) | 'gov'(정부·공공) | 'community'(커뮤니티)
//   feed     : RSS/Atom 주소 (fetch: fetchFeed)
//   filter   : true면 부동산 관련 제목만 남김 (경제 전체 피드, 타 부처 보도자료)
//   selectors: HTML 목록 파싱 (row/title/link/views/comments/likes/exclude)
//   keepParams : 글 주소에서 남길 쿼리 파라미터 (같은 글이 같은 id가 되도록)
//   detailV  : 상세 정보 뽑는 방식이 바뀌면 올림 → 저장된 상세 정보를 다시 받음
//   enrich   : 글의 상세 페이지에서 주요 정보(details)를 뽑는 함수. 새 글에 대해서만, 한 번에 몇 개씩 호출
//   replace  : 목록에서 빠진 글은 바로 숨김 (마감된 청약 공고 등)
//   boostHours : 앱 목록에서 이 소스 글을 더 위에 보이게 (최신순에서 이 시간만큼 새 글로 취급, 중요도 가산)
//   disabled : true면 수집 안 함
import { fetchFeed, fetchGoogleNews } from './feeds.js';
import { fetchKorea } from './korea.js';
import { fetchNaverCafe } from './navercafe.js';
import { fetchLh, fetchApplyhome, enrichApplyhome, enrichLh } from './apply.js';

export const SOURCES = [
  // ---- 언론 ----
  {
    id: 'gnews', name: '구글뉴스', board: '부동산 종합', kind: 'news', color: '#4285f4',
    url: 'https://news.google.com/', fetch: fetchGoogleNews, outletFromTitle: true, limit: 200,
    queries: ['부동산', '아파트 청약 분양', '공공분양 청약', '사전청약 OR 본청약', '무순위 청약 줍줍', '재건축 재개발', '전세 월세', '주택담보대출 금리', '집값 실거래가'],
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
  // ---- 청약·공공분양 공고 (목록에 있는 동안만 보여줌: replace) ----
  { id: 'applyhome', name: '청약홈', board: 'APT 분양정보', kind: 'gov', color: '#e4572e', url: 'https://www.applyhome.co.kr/ai/aia/selectAPTLttotPblancListView.do', list: 'selectAPTLttotPblancListView.do', detail: 'selectAPTLttotPblancDetail.do', label: '청약', fetch: fetchApplyhome, enrich: enrichApplyhome, detailV: 2, replace: true, maxAgeH: 24 * 45, boostHours: 4 },
  { id: 'applyhome-remain', name: '청약홈 무순위', board: '무순위·잔여세대', kind: 'gov', color: '#c2410c', url: 'https://www.applyhome.co.kr/ai/aia/selectAPTRemndrLttotPblancListView.do', list: 'selectAPTRemndrLttotPblancListView.do', detail: 'selectAPTRemndrLttotPblancDetail.do', label: '무순위', fetch: fetchApplyhome, replace: true, allowEmpty: true, maxAgeH: 24 * 45, boostHours: 4 },
  { id: 'lh-sale', name: 'LH 분양', board: '분양주택 공고', kind: 'gov', color: '#0e7490', url: 'https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancList.do?mi=1027', mi: 1027, fetch: fetchLh, enrich: enrichLh, detailV: 2, replace: true, maxAgeH: 24 * 60, boostHours: 4 },
  { id: 'lh', name: 'LH 임대', board: '임대주택 공고', kind: 'gov', color: '#155e75', url: 'https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancList.do?mi=1026', mi: 1026, types: /임대|행복|전세|든든/, fetch: fetchLh, enrich: enrichLh, detailV: 2, replace: true, maxAgeH: 24 * 60 },

  // 국토교통부 공식 홈페이지 보도자료 RSS (쿠키를 받아 다시 요청해야 열림)
  { id: 'molit', name: '국토교통부', board: '보도자료', kind: 'gov', color: '#14532d', url: 'https://www.molit.go.kr/USR/NEWS/m_71/lst.jsp', feed: 'https://www.molit.go.kr/dev/board/board_rss.jsp?rss_id=NEWS', fetch: fetchFeed, cookies: true, keepOnly: ['id'], filter: true },
  // 서울시 주택 분야 새소식 (보도자료·공고), 경기도 보도자료
  { id: 'seoul', name: '서울시', board: '주택 새소식', kind: 'gov', color: '#0b6db7', url: 'https://news.seoul.go.kr/citybuild/', feed: 'https://news.seoul.go.kr/citybuild/feed', fetch: fetchFeed, maxAgeH: 240, allowEmpty: true }, // 글이 주 몇 건이라 10일치
  { id: 'gg', name: '경기도', board: '보도자료', kind: 'gov', color: '#0f766e', url: 'https://gnews.gg.go.kr/briefing/brief_gongbo.do', feed: 'https://gnews.gg.go.kr/rss/gnewsRssBodo.do', fetch: fetchFeed, filter: true, maxAgeH: 96 },
  // 정책브리핑 목록 페이지 (RSS는 2026-07-01 종료). 모든 부처 보도자료 중 부동산 관련 제목만, 부처 이름이 매체명으로 들어감
  { id: 'korea-press', name: '정책브리핑', board: '부처 보도자료', kind: 'gov', color: '#1f6f43', url: 'https://www.korea.kr/briefing/pressReleaseList.do', pages: 6, filter: true, fetch: fetchKorea },
  { id: 'korea-policy', name: '정책뉴스', board: '정책브리핑', kind: 'gov', color: '#2b7a9e', url: 'https://www.korea.kr/news/policyNewsList.do', pages: 2, filter: true, fetch: fetchKorea },
  // 구글 뉴스에서 정부 사이트 글만 검색 (클라우드에서 받을 수 있는 정부 발표)
  {
    id: 'gov-search', name: '정부 발표 검색', board: '정부 사이트', kind: 'gov', color: '#3f7d5c',
    url: 'https://news.google.com/', fetch: fetchGoogleNews, outletFromTitle: true, limit: 40, windowDays: 3, filter: true,
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
  // 뽐뿌 부동산포럼: 목록 페이지는 클라우드를 막지만(403) RSS는 열림. 조회수는 없어서 최신순
  { id: 'ppomppu', name: '뽐뿌', board: '부동산포럼', kind: 'community', color: '#d9534f', url: 'https://m.ppomppu.co.kr/new/bbs_list.php?id=house', feed: 'https://www.ppomppu.co.kr/rss.php?id=house', fetch: fetchFeed, keepOnly: ['id', 'no'] },
  // 네이버 카페 부동산스터디 인기글
  { id: 'navercafe', name: '부동산스터디', board: '네이버 카페 인기글', kind: 'community', color: '#03c75a', url: 'https://cafe.naver.com/jaegebal', cafeId: 12730407, cafeUrl: 'jaegebal', fetch: fetchNaverCafe, filter: true, limit: 80, boostHours: 8 }, // 목록에서 더 위에 보이게
];

export const ACTIVE_SOURCES = SOURCES.filter((s) => !s.disabled);
export const sourceById = Object.fromEntries(SOURCES.map((s) => [s.id, s]));
