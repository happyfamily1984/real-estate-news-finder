import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseCount, parseList } from '../src/parser.js';
import { parseFeed } from '../src/feeds.js';
import { sourceById } from '../src/sources.js';
import { parseKoreaList } from '../src/korea.js';
import { parseCafePopular } from '../src/navercafe.js';
import { tagsOf, isRelevant, keywordWeight, threadPick } from '../src/topics.js';

const fixture = (n) => fs.readFileSync(new URL(`./fixtures/${n}`, import.meta.url), 'utf8');
const now = Date.parse('2026-11-15T03:00:00Z');
const xml = (n) => fixture(n).replaceAll('__NOW__', new Date(now - 3600_000).toUTCString());

test('parseCount: 다양한 숫자 표기', () => {
  assert.equal(parseCount('1,234'), 1234);
  assert.equal(parseCount('조회 1.2만'), 12000);
  assert.equal(parseCount('[12]'), 12);
  assert.equal(parseCount('-'), null);
});

test('디시 부동산갤: 공지 제외, 숫자 추출, 주소 정규화', () => {
  const posts = parseList(fixture('dcinside.html'), sourceById.dcinside);
  assert.equal(posts.length, 2);
  assert.equal(posts[0].url, 'https://gall.dcinside.com/board/view/?id=immovables&no=100');
  assert.equal(posts[0].views, 45678);
});

test('뽐뿌 부동산포럼 RSS: 주소에서 글 번호만 남김', () => {
  const rss = `<?xml version="1.0" encoding="UTF-8" ?><rss version="2.0"><channel><title>부동산포럼</title>
  <item><title>재건축 조합 질문</title><link>http://www.ppomppu.co.kr/zboard/view.php?id=house&amp;no=260240&amp;page=3</link><description>살고 있는 아파트가 재건축 조합설립된 아파트인데 질문이 있습니다...</description><pubDate>__NOW__</pubDate></item></channel></rss>`.replaceAll('__NOW__', new Date(now - 3600_000).toUTCString());
  const posts = parseFeed(rss, sourceById.ppomppu, now);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].url, 'http://www.ppomppu.co.kr/zboard/view.php?id=house&no=260240');
  assert.equal(posts[0].summary, '살고 있는 아파트가 재건축 조합설립된 아파트인데 질문이 있습니다...');
});

test('국토부 RSS 주소 정리 · 네이버 카페 인기글은 부동산 글만', () => {
  const rss = `<rss version="2.0"><channel><item><title><![CDATA[수도권 주택 공급 점검회의]]></title><link>https://www.molit.go.kr/USR/NEWS/m_71/dtl.jsp?id=95092598&#38;src=text&#38;kw=000004</link><description><![CDATA[<iframe src="x"></iframe>]]></description><pubDate>__NOW__</pubDate></item></channel></rss>`.replaceAll('__NOW__', new Date(now - 3600_000).toUTCString());
  const m = parseFeed(rss, sourceById.molit, now);
  assert.equal(m[0].url, 'https://www.molit.go.kr/USR/NEWS/m_71/dtl.jsp?id=95092598');
  assert.equal(m[0].summary, undefined);

  const json = { message: { result: { articleList: [
    { articleId: 1, subject: '개포래미안포레스트 실거주 후기', readCount: 1125, commentCount: 6, upCount: 20, writeDateTimestamp: now - 3600_000 },
    { articleId: 2, subject: '오늘 점심 뭐 먹지', readCount: 900, commentCount: 3, upCount: 1, writeDateTimestamp: now - 3600_000 },
    { articleId: 3, subject: '분당 아파트 입지분석', readCount: 998, commentCount: 11, upCount: 9, writeDateTimestamp: now - 100 * 3600_000 },
  ] } } };
  const c = parseCafePopular(json, sourceById.navercafe, now);
  assert.deepEqual(c.map((p) => p.url), ['https://cafe.naver.com/jaegebal/1']);
  assert.deepEqual([c[0].views, c[0].comments, c[0].likes], [1125, 6, 20]);
});

test('구글 뉴스: 매체명 분리, 포토·오래된 기사 제외', () => {
  const posts = parseFeed(xml('gnews.xml'), sourceById.gnews, now);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].title, '서울 아파트값 3주 연속 상승… 강남 신고가');
  assert.equal(posts[0].outlet, '한국경제');
  assert.equal(posts[0].url, 'https://news.google.com/rss/articles/CBMiAAA');
  assert.equal(posts[0].publishedAt, now - 3600_000);
  assert.equal(posts[0].summary, undefined);
});

test('RSS 피드: 부동산 관련만, 요약에서 태그 제거', () => {
  const posts = parseFeed(xml('korea.xml'), { id: 'fsc', filter: true }, now);
  assert.equal(posts.length, 1);
  assert.equal(posts[0].title, '스트레스 DSR 3단계 시행방안 발표');
  assert.equal(posts[0].summary, '금융위원회는 7월 1일부터 스트레스 DSR 3단계를 시행한다고 밝혔다.');
  assert.match(posts[0].url, /newsId=156700001/);
});

test('주제 태그 · 관련성 · 키워드 가중치', () => {
  assert.deepEqual(tagsOf('과천 무순위 청약에 10만명'), ['청약·분양']);
  assert.deepEqual(tagsOf('한은 기준금리 동결… 전세대출 금리 하락'), ['시세', '금리·대출', '전월세']);
  assert.equal(isRelevant('삼성전자 3분기 실적 발표'), false);
  assert.equal(isRelevant('수도권 아파트 입주 물량 감소'), true);
  assert.ok(keywordWeight('국토부 부동산 대책 발표') > keywordWeight('동네 산책 후기'));
});

test('스레드 추천: 독자가 궁금해할 글(대상·시행일·금액)이 업계 소식보다 높음', () => {
  const a = threadPick('스트레스 DSR 3단계 7월부터 시행… 대출 한도 최대 5000만원 줄어든다');
  const b = threadPick('청년 월세 지원 신청 대상·조건 총정리');
  const c = threadPick('OO건설, 3분기 영업이익 12% 증가');
  const d = threadPick('건설협회 창립 기념 행사 개최');
  assert.ok(a >= 80 && b >= 70, `${a} ${b}`);
  assert.ok(c <= 30 && d === 0, `${c} ${d}`);
});

test('정책브리핑 목록: 부동산 관련만, 부처 이름·날짜·요약 추출', () => {
  const html = `<ul>
    <li><a href="/briefing/pressReleaseView.do?newsId=156700001&amp;pageIndex=1"><span class="text"><strong>수도권 주택 공급 확대 방안 발표</strong><span class="lead">국토교통부는 수도권에 3만 가구를 공급한다.</span><span class="source"><span>2026.11.14</span><span>국토교통부</span></span></span></a></li>
    <li><a href="/briefing/pressReleaseView.do?newsId=156700002"><strong>가을철 산불방지대책본부 가동</strong><span class="source"><span>2026.11.15</span><span>산림청</span></span></a></li>
    <li><a href="/briefing/pressReleaseView.do?newsId=156700003"><strong>전세사기 피해자 지원 현황</strong><span class="source"><span>2026.11.15</span><span>국토교통부</span></span></a></li>
    <li><a href="/briefing/pressReleaseView.do?newsId=156600000"><strong>청약 제도 개선</strong><span class="source"><span>2026.11.01</span><span>국토교통부</span></span></a></li>
  </ul>`;
  const posts = parseKoreaList(html, sourceById['korea-press'], now);
  assert.deepEqual(posts.map((p) => p.title), ['수도권 주택 공급 확대 방안 발표', '전세사기 피해자 지원 현황']);
  assert.equal(posts[0].url, 'https://www.korea.kr/briefing/pressReleaseView.do?newsId=156700001');
  assert.equal(posts[0].outlet, '국토교통부');
  assert.equal(posts[0].summary, '국토교통부는 수도권에 3만 가구를 공급한다.');
  assert.equal(new Date(posts[0].publishedAt).toISOString(), '2026-11-14T03:00:00.000Z');
  assert.equal(posts[1].publishedAt, null); // 오늘 글은 처음 수집한 시각을 씀
});
