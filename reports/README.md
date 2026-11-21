# 보고서함

앱의 **통계 > 보고서함**에 보이는 보고서 목록입니다. 기본 브랜치의 `reports/reports.json`을 앱이 바로 받아갑니다
(`https://raw.githubusercontent.com/<owner>/<repo>/HEAD/reports/reports.json`, 반영까지 몇 분).

보고서를 올리려면 `reports` 배열 맨 앞에 항목을 추가하고 기본 브랜치에 합칩니다. 앱을 새로 만들 필요는 없습니다.

```json
{
  "id": "2026-11-21-주제",            // 겹치지 않는 이름 (올림 표시에 씀)
  "title": "보고서 제목 (카드 표지에 들어감)",
  "date": "2026-11-21",
  "summary": "목록에 보일 한두 줄",
  "thread": "스레드에 올릴 글 (500자 이내)",
  "talk": "오픈톡에 올릴 짧은 글 (비우면 thread를 씀)",
  "blog": { "title": "지역·핵심 낱말이 앞에 오는 제목", "body": "첫 문단 → ■ 소제목 3개 → ■ 한눈에 정리 → 마무리", "tags": ["태그"] },
  "cards": [ { "title": "카드 한 장의 제목", "points": ["항목: 내용", "항목: 내용"] } ],
  "sources": ["자료 이름 — https://주소"]
}
```

- 숫자·날짜는 `sources`에 적은 자료에 있는 것만 씁니다. 확인하지 못한 내용은 쓰지 않거나 확인하지 못했다고 적습니다.
- 카드 한 장에는 4줄 안팎, 항목 10자·내용 28자 이내가 보기 좋습니다.

## 보고서에 쓰는 자료 (공용 임시저장소)

보고서의 실거래 숫자는 중개사 슈퍼앱 저장소(`realtor-super-app`)의 `store` 브랜치에 모은 자료로 씁니다.

1. `realtor-super-app`의 Actions **collect**를 시군구·기간을 넣어 실행 → 국토부 실거래(매매·전월세)가 `store` 브랜치에 모임
2. `node collector/collect.js summary --regions 강남구 --months 6 --apt 은마`로 단지·면적별 건수·중간값·최고가를 뽑음
3. 뽑은 숫자로 보고서를 쓰고, `sources`에 "국토교통부 실거래가 공개시스템 (YYYY-MM-DD 받음)"을 적음

자료는 30일 뒤 지워지는 임시 자료라, 보고서에는 받은 날짜를 꼭 적습니다. 자세한 꼴은 `realtor-super-app`의 README.
