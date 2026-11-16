// 한 번 수집하고 결과를 OUT_DIR에 저장한 뒤 종료합니다. (GitHub Actions에서 주기 실행)
//   OUT_DIR/state.json : 다음 실행 때 이어서 쓰는 전체 상태
//   OUT_DIR/posts.json : 앱이 받아가는 피드 (중요도순)
import fs from 'node:fs';
import path from 'node:path';
import { Crawler } from './crawler.js';
import { ACTIVE_SOURCES as SOURCES } from './sources.js';
import { TOPICS } from './topics.js';
import { demoFetchItems } from './demo.js';
import { runStats } from './stats.js';

const outDir = path.resolve(process.env.OUT_DIR || 'out');
const demo = process.env.DEMO === '1';

const crawler = new Crawler({ dataDir: outDir, fetchItems: demo ? demoFetchItems : undefined });
await crawler.crawl();

const opt = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v != null && v !== '' && !(Array.isArray(v) && v.length === 0)));
const feed = {
  version: 1,
  updatedAt: crawler.lastCrawlAt,
  topics: TOPICS.map((t) => t.id),
  sources: SOURCES.map(({ id, name, board, url, color, kind, boostHours }) => ({ id, name, board, url, color, kind, ...(boostHours ? { boostHours } : {}), status: crawler.status[id] })),
  posts: crawler.list({ sort: 'score', limit: 700 }).map((p) => ({
    id: p.id, source: p.source, title: p.title, url: p.url, views: p.views, comments: p.comments, likes: p.likes,
    score: p.score, pick: p.pick ?? 0, viewsPerMin: p.viewsPerMin, firstSeenAt: p.firstSeenAt, rank: p.rank,
    ...opt({ publishedAt: p.publishedAt, tags: p.tags, related: p.related || null, outlet: p.outlet, summary: p.summary, dupOf: p.dupOf }),
  })),
};
fs.writeFileSync(path.join(outDir, 'posts.json'), JSON.stringify(feed));

for (const s of SOURCES) {
  const st = crawler.status[s.id];
  console.log(`${st.ok ? '✓' : '✗'} ${s.name}(${s.id}): ${st.ok ? `${st.count}개` : st.error}`);
}
if (!demo) await runStats(outDir); // 통계 탭용 stats.json (KB부동산)

const okCount = Object.values(crawler.status).filter((s) => s.ok).length;
console.log(`수집 완료 ${okCount}/${SOURCES.length}, 피드 글 ${feed.posts.length}개 → ${outDir}`);
if (okCount === 0 && !demo) process.exit(1);
