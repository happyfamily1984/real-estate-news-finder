// 중요도 점수 (0~100)
// 뉴스·정부: 최신성(35) + 여러 매체가 같이 다룬 정도(30) + 제목 키워드(15) + 독자 관심도(20). 정부 발표는 +10
// pick(스레드 추천, 0~100): 독자 관심도 위주 + 화제성 + 최신성
// 커뮤니티: 같은 게시판 안에서의 백분위(조회·댓글·추천) 85 + 분당 조회 증가 15
import { keywordWeight, threadPick } from './topics.js';

const METRICS = ['views', 'comments', 'likes'];
const WEIGHT = { views: 1, comments: 1.2, likes: 1 };
const SIMILAR = 0.42; // 제목 유사도(글자 2개 묶음 자카드) 기준

function percentileMap(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const n = sorted.length;
  return (v) => {
    if (n <= 1) return 0.5;
    let lo = 0;
    while (lo < n && sorted[lo] < v) lo++;
    let hi = lo;
    while (hi < n && sorted[hi] === v) hi++;
    return ((lo + hi - 1) / 2) / (n - 1);
  };
}

export function bigrams(title) {
  const s = title.replace(/\[[^\]]{1,12}\]|\([^)]{1,12}\)/g, '').replace(/[^0-9A-Za-z가-힣]/g, '');
  const set = new Set();
  for (let i = 0; i < s.length - 1; i++) set.add(s.slice(i, i + 2));
  return set;
}

export function similarity(a, b) {
  if (a.size === 0 || b.size === 0) return 0;
  let inter = 0;
  for (const x of a) if (b.has(x)) inter++;
  return inter / (a.size + b.size - inter);
}

function scoreCommunity(group) {
  const pct = {};
  for (const m of METRICS) {
    const vals = group.map((p) => p[m]).filter((v) => v != null);
    if (vals.length >= 2) pct[m] = percentileMap(vals);
  }
  group.forEach((p, idx) => {
    let sum = 0;
    let w = 0;
    for (const m of METRICS) {
      if (pct[m] && p[m] != null) {
        sum += pct[m](p[m]) * WEIGHT[m];
        w += WEIGHT[m];
      }
    }
    const base = w > 0 ? sum / w : 1 - idx / Math.max(group.length - 1, 1);
    const surge = Math.min(p.viewsPerMin ?? 0, 200) / 200;
    p.score = Math.round(Math.min(100, base * 85 + surge * 15));
    p.related = 0;
    p.pick = Math.round(threadPick(p.title) * 0.6 + p.score * 0.4);
  });
}

/** posts: 글 배열, kindOf: source id -> kind */
export function scorePosts(posts, kindOf = () => 'community', now = Date.now()) {
  const community = new Map();
  const articles = [];
  for (const p of posts) {
    if (kindOf(p.source) === 'community') {
      if (!community.has(p.source)) community.set(p.source, []);
      community.get(p.source).push(p);
    } else articles.push(p);
  }
  for (const group of community.values()) scoreCommunity(group);

  // 같은 주제를 다룬 다른 매체 수
  const grams = articles.map((p) => bigrams(p.title));
  const outlet = (p) => p.outlet || p.source;
  articles.forEach((p, i) => {
    const others = new Set();
    for (let j = 0; j < articles.length; j++) {
      if (i === j || outlet(articles[j]) === outlet(p)) continue;
      if (similarity(grams[i], grams[j]) >= SIMILAR) others.add(outlet(articles[j]));
    }
    p.related = others.size;
    const ageH = Math.max(0, now - (p.publishedAt ?? p.firstSeenAt ?? now)) / 3600_000;
    const recency = Math.pow(0.5, ageH / 6); // 6시간마다 절반
    const cluster = Math.min(others.size, 4) / 4;
    const bonus = kindOf(p.source) === 'gov' ? 10 : 0;
    const interest = threadPick(p.title);
    p.score = Math.round(Math.min(100, recency * 35 + cluster * 30 + keywordWeight(p.title) * 15 + interest * 0.2 + bonus));
    p.pick = Math.round(Math.min(100, interest * 0.65 + cluster * 20 + recency * 15));
  });
  return posts;
}
