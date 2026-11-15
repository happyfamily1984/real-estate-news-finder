import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Crawler } from '../src/crawler.js';
import { scorePosts, bigrams, similarity } from '../src/score.js';

const now = Date.now();
const news = (source, n, title, ageH = 1, outlet) => ({ id: `${source}:${n}`, source, title, url: `https://${source}.test/${n}`, views: null, comments: null, likes: null, publishedAt: now - ageH * 3600_000, ...(outlet ? { outlet } : {}) });
const comm = (source, n, views) => ({ id: `${source}:${n}`, source, title: `글 ${n}`, url: `https://${source}.test/${n}`, views, comments: null, likes: null });
const kindOf = (id) => ({ a: 'news', b: 'news', g: 'gov', c: 'community', d: 'community' }[id]);

test('제목 유사도: 같은 사건을 다룬 제목은 묶임', () => {
  const s = similarity(bigrams('서울 아파트값 3주 연속 상승… 강남 신고가'), bigrams('[속보] 서울 아파트값 3주 연속 상승, 강남권 신고가 속출'));
  assert.ok(s >= 0.42, `유사도 ${s}`);
  assert.ok(similarity(bigrams('서울 아파트값 3주 연속 상승'), bigrams('전세사기 특별법 국회 통과')) < 0.2);
});

test('점수: 여러 매체가 다룬 최신 글 > 단독 글 > 오래된 글, 정부 가산, 커뮤니티는 상대 순위', () => {
  const posts = scorePosts([
    news('a', 1, '서울 아파트값 3주 연속 상승 강남 신고가'),
    news('b', 2, '서울 아파트값 3주 연속 상승… 강남권 신고가'),
    news('a', 3, '동네 공원 리모델링 소식'),
    news('a', 4, '동네 도서관 개관 소식', 30),
    news('g', 5, '동네 체육관 개관 소식'),
    comm('c', 6, 10), comm('c', 7, 100), comm('d', 8, 100000), comm('d', 9, 1000000),
  ], kindOf, now);
  const s = Object.fromEntries(posts.map((p) => [p.id, p]));
  assert.equal(s['a:1'].related, 1);
  // 같은 주제 묶음은 한 글만 대표로 남음
  assert.equal([s['a:1'], s['b:2']].filter((p) => p.dupOf).length, 1);
  assert.equal(s['a:3'].dupOf, undefined);
  assert.ok(s['a:1'].score > s['a:3'].score);
  assert.ok(s['a:3'].score > s['a:4'].score);
  assert.ok(s['g:5'].score > s['a:3'].score);
  assert.equal(s['c:7'].score, s['d:9'].score);
  assert.ok(s['c:7'].score > s['c:6'].score);
});

test('크롤러: 첫 수집은 알림 없음, 새 글만 알림, 실패 기록, 뉴스는 피드에서 밀려도 유지', async () => {
  const src = [{ id: 'a', name: 'A', kind: 'news', url: 'https://a.test/' }, { id: 'b', name: 'B', kind: 'news', url: 'https://b.test/' }, { id: 'c', name: 'C', kind: 'community', url: 'https://c.test/' }];
  let round = 0;
  const notified = [];
  const c = new Crawler({
    sources: src,
    onNewPosts: (p) => notified.push(...p.map((x) => x.id)),
    fetchItems: async (s) => {
      if (s.id === 'b') throw new Error('HTTP 403');
      if (s.id === 'c') return round === 0 ? [comm('c', 1, 10), comm('c', 2, 20)] : [comm('c', 1, 40)];
      return round === 0 ? [news('a', 1, '전세 대책 발표')] : [news('a', 2, '청약 일정 공개')];
    },
  });
  await c.crawl();
  assert.deepEqual(notified, []);
  assert.equal(c.status.b.error, 'HTTP 403');
  assert.deepEqual(c.posts.get('a:1').tags, ['정책', '전월세']);

  round = 1;
  await c.crawl();
  assert.deepEqual(notified, ['a:2']);
  assert.deepEqual(c.list({ sort: 'latest' }).map((p) => p.id).sort(), ['a:1', 'a:2', 'c:1']);
});
