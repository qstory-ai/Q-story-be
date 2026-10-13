# 공공데이터 유치원·어린이집 목록 적재

원장 기관 등록(목록에서 고르기)과 선생님 소속 신청(검색)에 쓰는 `public_institution` 표(083)를 채운다.
앱 서버가 아니라 운영자가 손으로 돌린다. **원천 파일은 저장소에 넣지 않는다.**

## 원천

| 종류 | 원천 | 받는 방법 | 라이선스 | 갱신 |
| --- | --- | --- | --- | --- |
| 유치원 | 교육부 유치원알리미 [공시자료 다운로드](https://e-childschoolinfo.moe.go.kr/openData.do) - 일반 현황(공시항목 05), 전체 시/도, JSON | 키 없음. 적재 명령이 직접 받는다 | 공공누리 제1유형(출처표시) | 공시 1년 2회(1차·2차) |
| 어린이집 | 한국사회보장정보원 어린이집정보공개포털 오픈API `cpmsapi030`(어린이집 기본정보, 시군구 코드마다) | **인증키 필요**(`CHILDCARE_API_KEY`) | 공공누리 제1유형(data.go.kr 15013108) | 수시 |

- data.go.kr의 "전국유치원표준데이터"(15096279)는 유치원알리미 오픈API로 이어지는 링크형이라 키가 필요하고 공공누리 제3유형(변경금지)으로 적혀 있다. 우리는 같은 공시를 키 없이 받는 유치원알리미 다운로드(제1유형)를 쓴다.
- 화면에는 출처를 늘 보여 준다(검색 결과 아래, 서버 `PublicInstitutionSearchService.ATTRIBUTION`).
- 개인정보: 원천에는 대표자명·원장명·기관 전화가 있지만 **적재하지 않는다**. 저장하는 것은 기관 이름·주소·시도·시군구·설립유형·운영 여부뿐이다.
- 유치원 다운로드에는 유치원 코드가 없어 `교육지원청|유치원명|설립일`을 원천 키로 쓴다(2026년 1차 7,152곳에서 겹침 없음). 이름이 바뀌면 새 줄로 들어가고, `--close-missing`이면 옛 줄은 CLOSED가 된다(이미 그 줄로 등록한 기관은 연결이 그대로 남는다).

## 명령

DB 접속은 앱과 같은 `SUPABASE_DB_URL` / `SUPABASE_DB_USERNAME` / `SUPABASE_DB_PASSWORD` 환경 변수. 083이 적용된 뒤(앱 배포 뒤)에 돌린다.

```bash
# 1) 먼저 확인만(DB에 안 넣음) - 건수와 시도별 분포가 나온다
./gradlew importPublicInstitutions -Pargs="kindergarten --timing 20261 --dry-run"

# 2) 넣기. 같은 기관은 고쳐 쓰고(여러 번 돌려도 같다), --close-missing이면 이번 파일에 없는 기관을 CLOSED로 돌린다.
#    이번 건수가 지금 운영 중 건수의 절반보다 적으면 --close-missing을 거절한다(잘못 받은 일부 파일 보호).
./gradlew importPublicInstitutions -Pargs="kindergarten --timing 20261 --close-missing"

# 받아 둔 파일로 넣기
./gradlew importPublicInstitutions -Pargs="kindergarten --timing 20261 --file C:/data/kinder-20261.json"
```

`--timing`은 공시 차수(2026년 1차 = `20261`, 2차 = `20262`). 새 차수가 나오면 같은 명령을 차수만 바꿔 다시 돌린다.

어린이집(키를 받은 뒤):

```bash
# arcodes.txt: 시군구 코드 5자리, 한 줄에 하나(행정표준코드관리시스템 법정동코드의 앞 5자리 중복 제거)
export CHILDCARE_API_KEY=...   # 출력하지 말 것
./gradlew importPublicInstitutions -Pargs="daycare --arcodes-file C:/data/arcodes.txt --save-dir C:/data/daycare --dry-run"
./gradlew importPublicInstitutions -Pargs="daycare --xml-dir C:/data/daycare --close-missing"
```

어린이집 응답 필드(stcode·crname·crtypename·crstatusname·craddr·sidoname·sigunname)는 공개 명세 기준이며 실제 응답으로는 아직 확인하지 못했다. 키를 받으면 `--dry-run`으로 먼저 확인한다. 운영현황이 정상·재개가 아니면(휴지·폐지) CLOSED로 넣어 검색에서 뺀다.
