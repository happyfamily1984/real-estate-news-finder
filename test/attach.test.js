import { test } from 'node:test';
import assert from 'node:assert/strict';
import zlib from 'node:zlib';
import { unzip, hwpxText, trimAttach, findAttachments } from '../src/attach.js';

/** 시험용 zip 만들기 (압축한 항목과 그대로 넣은 항목) */
function makeZip(files) {
  const locals = []; const centrals = [];
  let offset = 0;
  for (const [name, content, store] of files) {
    const nameBuf = Buffer.from(name, 'utf8');
    const raw = Buffer.from(content, 'utf8');
    const data = store ? raw : zlib.deflateRawSync(raw);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(store ? 0 : 8, 8); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(raw.length, 22); local.writeUInt16LE(nameBuf.length, 26);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(store ? 0 : 8, 10); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(raw.length, 24); central.writeUInt16LE(nameBuf.length, 28); central.writeUInt32LE(offset, 42);
    locals.push(local, nameBuf, data); centrals.push(central, nameBuf);
    offset += 30 + nameBuf.length + data.length;
  }
  const cd = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

const section = (paras) => `<?xml version="1.0"?><hs:sec xmlns:hp="x">${paras.map((p) => `<hp:p id="1"><hp:run><hp:t>${p}</hp:t></hp:run></hp:p>`).join('')}</hs:sec>`;

test('hwpx에서 글자 뽑기: 문단마다 줄바꿈, 구역 순서대로', () => {
  const zip = makeZip([
    ['mimetype', 'application/hwp+zip', true],
    ['Contents/section1.xml', section(['둘째 구역 문단'])],
    ['Contents/section0.xml', section(['수도권 주택공급 확대방안', '□ 2027년까지 &lt;3기 신도시&gt; 5만 호 착공', '□ 2027년까지 &lt;3기 신도시&gt; 5만 호 착공', '<hp:tab/>표 안 글자'])],
  ]);
  assert.equal(unzip(zip).get('mimetype').toString(), 'application/hwp+zip');
  assert.equal(hwpxText(zip), '수도권 주택공급 확대방안\n□ 2027년까지 <3기 신도시> 5만 호 착공\n표 안 글자\n둘째 구역 문단'); // 연달아 같은 줄은 한 번만
  assert.equal(hwpxText(Buffer.from('zip이 아님')), '');
  assert.equal(hwpxText(makeZip([['Preview/PrvText.txt', '미리보기 첫 줄\r\n둘째 줄']])), '미리보기 첫 줄\n둘째 줄');
});

test('요약에 넣을 만큼만 자름', () => {
  const text = Array.from({ length: 50 }, (_, i) => `문단 ${i} ` + '가'.repeat(30)).join('\n');
  const cut = trimAttach(text, 300);
  assert.ok(cut.length <= 300 && !cut.endsWith('가가\n') && text.startsWith(cut));
  assert.equal(trimAttach('짧은 글', 300), '짧은 글');
});

test('보도자료 페이지에서 첨부파일 링크: hwpx 먼저', () => {
  const html = `<ul>
    <li><span>261116 (보도설명) 조선일보, 세계일보_f.pdf</span><a href="/common/download.do?fileId=2&amp;tblKey=GMN">내려받기</a><a href="/docViewer/2">바로보기</a></li>
    <li><span>261116 (보도설명) 조선일보, 세계일보_f.hwpx</span><a href="/common/download.do?fileId=1&amp;tblKey=GMN">내려받기</a></li>
    <li><a href="/briefing/list.do">목록</a></li></ul>`;
  const files = findAttachments(html, 'https://www.korea.kr/briefing/pressReleaseView.do?newsId=1');
  assert.deepEqual(files.map((f) => [f.ext, f.url]), [
    ['hwpx', 'https://www.korea.kr/common/download.do?fileId=1&tblKey=GMN'],
    ['pdf', 'https://www.korea.kr/common/download.do?fileId=2&tblKey=GMN'],
  ]);
  assert.ok(files[0].name.endsWith('세계일보_f.hwpx'));
});
