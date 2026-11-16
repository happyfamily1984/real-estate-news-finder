// 진단: 청약홈 무순위 상세 페이지 내용과 해석 결과
import { fetchHtml } from './fetcher.js';
import { parseApplyhomeDetail } from './apply.js';

const base = 'https://www.applyhome.co.kr/ai/aia/';
const list = await fetchHtml(base + 'selectAPTRemndrLttotPblancListView.do', { cookies: true, timeoutMs: 20000 });
const ids = [...list.matchAll(/data-pbno="(\d+)"/g)].map((m) => m[1]).slice(0, 2);
for (const id of ids) {
  const html = await fetchHtml(`${base}selectAPTRemndrLttotPblancDetailView.do?houseManageNo=${id}&pblancNo=${id}`, { cookies: true, timeoutMs: 20000 });
  const text = html.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/g, '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ');
  console.log('\n###', id, '\n본문:', text.slice(0, 2500));
  console.log('해석:', JSON.stringify(parseApplyhomeDetail(html)));
}
