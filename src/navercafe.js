// 네이버 카페 인기글 (로그인 없이 열리는 목록 API). 글 본문은 카페 가입·로그인이 필요할 수 있어 앱에서 네이버에 로그인해 봄
import { fetchJson } from './fetcher.js';
import { isRelevant } from './topics.js';

// 카페 글 제목에서 흔한 부동산 표현 (기사 제목용 기준보다 넓게: 단지 후기, 입지, 임장, 지역명 등)
const CAFE_TERMS = /실거주|입지|임장|매수|매도|갈아타기|상급지|하급지|단지|평형|\d+평|학군|역세권|구축|신축|급매|호가|실거래|갭|투자|부동산|집주인|세입자|입주|분양|청약|재건축|재개발|강남|서초|송파|잠실|마포|용산|성동|목동|분당|판교|과천|동탄|광교|수지|위례|하남|마곡|일산|평촌|송도|검단|래미안|자이|푸르지오|힐스테이트|아이파크|롯데캐슬|e편한|포레/;

const MUA = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

export function parseCafePopular(json, source, now = Date.now()) {
  const list = json?.message?.result?.articleList ?? [];
  const out = [];
  for (const a of list) {
    const title = String(a.subject ?? '').replace(/\s+/g, ' ').trim();
    if (!a.articleId || !title) continue;
    // 인기글에는 정치·잡담이 많이 섞여 있어 부동산 얘기인 제목만 남김
    if (source.filter && !(isRelevant(title) || CAFE_TERMS.test(title))) continue;
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
