// 네이버 카페 인기글 (로그인 없이 열리는 목록 API). 글 본문은 카페 가입·로그인이 필요할 수 있어 앱에서 네이버에 로그인해 봄
import { fetchJson } from './fetcher.js';
import { isRelevant } from './topics.js';

const MUA = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

export function parseCafePopular(json, source, now = Date.now()) {
  const list = json?.message?.result?.articleList ?? [];
  const out = [];
  for (const a of list) {
    const title = String(a.subject ?? '').replace(/\s+/g, ' ').trim();
    if (!a.articleId || !title) continue;
    // 인기글에는 정치·잡담이 많이 섞여 있어 부동산 관련 제목만 남김
    if (source.filter && !isRelevant(title)) continue;
    const publishedAt = Number.isFinite(a.writeDateTimestamp) ? Math.min(a.writeDateTimestamp, now) : null;
    if (publishedAt && now - publishedAt > 72 * 3600_000) continue;
    out.push({
      id: `${source.id}:${a.articleId}`, source: source.id, title,
      url: `https://cafe.naver.com/${source.cafeUrl}/${a.articleId}`,
      views: a.readCount ?? null, comments: a.commentCount ?? null, likes: a.upCount ?? null, publishedAt,
    });
  }
  return out.slice(0, source.limit ?? 60);
}

export async function fetchNaverCafe(source) {
  const json = await fetchJson(`https://apis.naver.com/cafe-web/cafe2/WeeklyPopularArticleListV3.json?cafeId=${source.cafeId}&mobileWeb=true&adUnit=PC_CAFE_BOARD&ad=false`, { userAgent: MUA, timeoutMs: 15000 });
  const total = json?.message?.result?.articleList?.length ?? 0;
  if (total === 0) throw new Error(json?.message?.error?.msg || '인기글 목록이 비어 있음');
  return parseCafePopular(json, source);
}
