// 진단: SH·GH 공고 상세 주소가 그대로 열리는지 (앱에서 여는 주소)
import { fetchHtml } from './fetcher.js';
import { fetchSh, fetchGh } from './apply.js';
import { sourceById } from './sources.js';

for (const [id, f] of [['sh-sale', fetchSh], ['sh', fetchSh], ['gh', fetchGh]]) {
  try {
    const posts = await f(sourceById[id]);
    for (const p of posts.slice(0, 8)) console.log(' ', p.title, '|', p.summary, '|', p.views);
    if (!posts[0]) continue;
    const html = await fetchHtml(posts[0].url, { cookies: true, timeoutMs: 20000 });
    const text = html.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/g, '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ');
    const key = posts[0].title.replace(/^\[[^\]]+\]\s*/, '').slice(0, 12);
    console.log(' 상세', posts[0].url, '길이', html.length, '제목 포함:', text.includes(key), '| 위치 주변:', text.slice(Math.max(0, text.indexOf(key) - 50), text.indexOf(key) + 500));
  } catch (e) {
    console.log(id, '실패:', e.message);
  }
}
