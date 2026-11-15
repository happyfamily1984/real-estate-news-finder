import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { parseCount, parseList } from '../src/parser.js';
import { parseFeed } from '../src/feeds.js';
import { sourceById } from '../src/sources.js';
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

test('뽐뿌 부동산포럼: 공지 제외, 페이지 파라미터 제거', () => {
  const posts = parseList(fixture('ppomppu.html'), sourceById.ppomppu);
  assert.equal(sourceById.ppomppu.disabled, true);
  assert.equal(posts.length, 1);
  assert.deepEqual(posts[0], {
    id: 'ppomppu:https://www.ppomppu.co.kr/zboard/view.php?id=house&no=12345',
    source: 'ppomppu', title: '전세 재계약 5% 인상 질문',
    url: 'https://www.ppomppu.co.kr/zboard/view.php?id=house&no=12345',
    views: 1204, comments: 7, likes: 3,
  });
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

test('정부 보도자료: 부동산 관련만, 요약에서 태그 제거', () => {
  const posts = parseFeed(xml('korea.xml'), sourceById.fsc, now);
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
