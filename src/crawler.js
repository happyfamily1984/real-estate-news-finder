import fs from 'node:fs';
import path from 'node:path';
import { ACTIVE_SOURCES as SOURCES } from './sources.js';
import { fetchHtml } from './fetcher.js';
import { parseList } from './parser.js';
import { scorePosts } from './score.js';
import { tagsOf, isRelevant } from './topics.js';

const KEEP_MS = 36 * 60 * 60 * 1000; // 36시간 지난 글은 정리
const KEEP_GOV_MS = 96 * 60 * 60 * 1000; // 정부 발표는 4일 (주말에는 새 글이 거의 없음)

export class Crawler {
  constructor({ dataDir, fetchItems, sources = SOURCES, onNewPosts = () => {} } = {}) {
    // fetchItems(source) -> 글 배열. 기본값은 실제 사이트를 가져와 파싱
    this.fetchItems = fetchItems ?? (async (source) => (source.fetch ? source.fetch(source) : parseList(await fetchHtml(source.url), source)));
    this.sources = sources;
    this.kindOf = (id) => this.sources.find((s) => s.id === id)?.kind ?? 'community';
    this.nameOf = (id) => this.sources.find((s) => s.id === id)?.name ?? id;
    this.onNewPosts = onNewPosts;
    this.posts = new Map();
    this.status = {}; // source id -> { ok, count, error, at }
    this.lastCrawlAt = null;
    this.file = dataDir ? path.join(dataDir, 'state.json') : null;
    this.load();
  }

  load() {
    if (!this.file || !fs.existsSync(this.file)) return;
    try {
      const arr = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      for (const p of arr) this.posts.set(p.id, p);
      this.hasCrawledBefore = this.posts.size > 0;
    } catch (e) {
      console.warn('[crawler] 저장된 데이터를 읽지 못했습니다:', e.message);
    }
  }

  save() {
    if (!this.file) return;
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    fs.writeFileSync(this.file, JSON.stringify([...this.posts.values()]));
  }

  async crawlSource(source, now) {
    try {
      const items = await this.fetchItems(source);
      // 부동산 글만 거르는 소스는 그 시각에 해당 글이 없을 수 있음 → 0개도 정상
      if (items.length === 0 && !source.filter && !source.allowEmpty) throw new Error('글을 찾지 못했습니다 (사이트 구조 변경 또는 차단 가능성)');
      this.status[source.id] = { ok: true, count: items.length, at: now };
      return items;
    } catch (e) {
      this.status[source.id] = { ok: false, count: 0, error: e.message, at: now };
      return [];
    }
  }

  async crawl() {
    const now = Date.now();
    const results = await Promise.all(this.sources.map((s) => this.crawlSource(s, now)));
    const fresh = [];

    results.forEach((items, si) => {
      const src = this.sources[si];
      const current = new Set(items.map((it) => it.id));
      items.forEach((item, rank) => {
        const prev = this.posts.get(item.id);
        const p = { ...prev, ...item, rank: rank + 1, lastSeenAt: now, onBoard: true, tags: tagsOf(item.title) };
        if (!prev) {
          p.firstSeenAt = now;
          p.viewsPerMin = 0;
          fresh.push(p);
        } else if (prev.views != null && item.views != null && now > prev.lastSeenAt) {
          const mins = (now - prev.lastSeenAt) / 60000;
          p.viewsPerMin = Math.max(0, Math.round((item.views - prev.views) / mins));
        }
        this.posts.set(p.id, p);
      });
      // 커뮤니티는 인기글 목록에서 빠진 글을 숨김. 뉴스·정부 글은 피드에서 밀려나도 기한까지 유지
      if (this.status[src.id]?.ok && (src.kind === 'community' || src.replace)) {
        for (const p of this.posts.values()) {
          if (p.source === src.id && !current.has(p.id)) p.onBoard = false;
        }
      }
    });

    for (const [id, p] of this.posts) {
      const src = this.sources.find((s) => s.id === p.source);
      const base = src?.kind === 'community' || src?.replace ? p.lastSeenAt : (p.publishedAt ?? p.firstSeenAt);
      const keep = src?.maxAgeH ? src.maxAgeH * 3600_000 : src?.kind === 'gov' ? KEEP_GOV_MS : KEEP_MS;
      // 없어진 소스의 글, 기한 지난 글, 부동산 글만 남기는 소스에 예전 기준으로 들어온 무관한 글 정리
      if (!src || now - base > keep || (src.filter && !isRelevant(p.title))) this.posts.delete(id);
    }

    scorePosts([...this.posts.values()].filter((p) => p.onBoard), this.kindOf, now, this.nameOf);
    // 우선 노출 소스(부동산스터디 등)는 중요도에 가산
    const listed = new Set(this.sources.filter((s) => s.replace && s.kind !== 'community').map((s) => s.id));
    for (const p of this.posts.values()) if (p.onBoard && listed.has(p.source)) p.score = Math.max(p.score ?? 0, 45); // 접수 중인 공고는 오래돼도 일정 점수
    const boosted = new Set(this.sources.filter((s) => s.boostHours).map((s) => s.id));
    for (const p of this.posts.values()) if (p.onBoard && boosted.has(p.source)) p.score = Math.min(100, (p.score ?? 0) + 12);
    this.lastCrawlAt = now;
    this.save();

    // 처음 실행할 때는 기존 글 전부가 '새 글'이므로 알림을 보내지 않음
    if (this.hasCrawledBefore && fresh.length) this.onNewPosts(fresh);
    this.hasCrawledBefore = true;
    return fresh;
  }

  list({ source, sort = 'score', q, limit = 200 } = {}) {
    let arr = [...this.posts.values()].filter((p) => p.onBoard);
    if (source) arr = arr.filter((p) => p.source === source);
    if (q) {
      const k = q.toLowerCase();
      arr = arr.filter((p) => p.title.toLowerCase().includes(k));
    }
    const key = {
      score: (p) => p.score ?? 0,
      views: (p) => p.views ?? -1,
      latest: (p) => p.publishedAt ?? p.firstSeenAt,
    }[sort] ?? ((p) => p.score ?? 0);
    arr.sort((a, b) => key(b) - key(a));
    return arr.slice(0, limit);
  }
}
