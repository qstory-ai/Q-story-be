# Q-story-be — 에이전트 작업 안내

Java 21 · Spring Boot 3.5 · PostgreSQL(Flyway) · JWT. 도메인 패키지 `src/main/java/com/qstory/backend/<도메인>/{controller,service,repository,entity,dto}`.
공개 저장소다: 커밋·PR·주석에 호스팅·지역·환경 변수 값·운영 절차를 쓰지 않는다.

## 확인 명령
- 테스트: `JAVA_HOME=~/.jdks/ms-21.0.9 ./gradlew test -q` (JDK 21 필요). 실패 상세는 `build/test-results/test/*.xml`의 `<failure`.
- 컴파일만: `./gradlew compileJava -q`
- 그라파나 코드: `cd ops/grafana-alerting && terraform fmt -check . && terraform init -backend=false && terraform validate`

## 어디에 무엇이
| 하는 일 | 위치 |
| --- | --- |
| 그레텔 대화(아이 말 → 답) | `companionchat/service/CompanionChatPipelineService.java`, 상호작용 `interaction/` |
| LLM·TTS·STT·이미지 공급자 | `provider/openrouter`(LLM, 예비 모델 목록), `provider/gemini`(TTS, 하루 호출 한도 있음), `provider/rtzr`(STT), `provider/higgsfield`(이미지), 실시간 STT `sttstream/` |
| 로그인·가입·탈퇴 | `identity/` - `service/AuthService`, `controller/AuthController`·`OAuthController`, 로그인 기록 `repository/LoginLogRepository`, 탈퇴 `service/AccountErasureService` |
| 자체 통계(베타 이벤트) | `betaevents/` - 대기열로 받아 묶음 저장, 허용 키는 `util/BetaEventValidator` |
| 이야기·장면·리포트 | `story`, `storyadmin`, `storyreport`, `narration`, `playsession` |
| 출시 알림·1분 후기·협업 문의·마이페이지 요청 | `launchnotification`, `completionsurvey`, `partnership`, `feedback` |
| 디스코드 알림(30분 묶음·경보) | `discord/service/DiscordMessages`(문구), `discord/repository/DiscordMetricsRepository`(집계 SQL) |
| GA4 일일 가져오기 | `analytics/` (`GaImportService`, `GaMetricsRepository`) |
| 오류 응답 | `common/error` - `ApiException.contractError(ErrorCode, "사용자 문구"[, status])` |
| 설정 | `src/main/resources/application.yml`의 `qstory.*` ↔ `config/AppProperties` |
| 요청 상한 | `ratelimit/` (`RateLimitRule`) |

## DB 마이그레이션 (`src/main/resources/db/schema`)
- 파일 이름 `NNN-설명.sql`, 번호는 지금 마지막 번호 + 1. 이미 적용된 파일은 고치지 않고 새 번호로 덧붙인다.
- 그라파나용 뷰는 `grafana` 스키마에 둔다. 끝에 `anon`·`authenticated` revoke, `grafana_reader` grant(있을 때만) 블록을 붙인다 - 093·095가 본보기.
- `create or replace view`는 칸을 **뒤에만** 더할 수 있다(이름·순서·타입이 같아야 함).
- `app_user`를 참조하는 칸을 새로 만들면 `AccountErasureService`에서 처리하고 `AccountErasureCoverageTest.HANDLED`에 적는다(테스트가 잡는다).
- 테스트 계정 판정은 `grafana.looks_like_test(texts[], email)`(093) 하나로 한다.

## 코드 관례
- 원시 SQL 저장소는 `NamedParameterJdbcTemplate` + `static final String` SQL 상수, 테스트는 mock jdbc로 상수·바인딩 확인(`GaMetricsRepositoryTest`, `LoginLogRepositoryTest`).
- 로그는 `event=이름 key=value` 한 줄(Loki로 감). 개인 정보·발화 원문은 로그에 남기지 않는다.
- 디스코드·그라파나에 내보내는 자유 글은 연락처를 가린다(`DiscordMessages.feedbackPreview`, 091 뷰의 정규식).
- 주석은 한국어, 왜 그런지 위주로.

## 그라파나 (`ops/grafana-alerting`, Terraform)
- PR에서 plan, main 푸시 때 apply(GitHub Actions `grafana-alerting`). 콘솔에서 바꾼 건 apply 때 되돌아간다.
- 대시보드 파일: `dashboards.tf`(백엔드 상태·대화 품질·UT 회차), `dashboards_growth.tf`(유입 → 전환), `dashboards_responses.tf`(사용자 응답, 공용 꾸밈 locals `this_vs_prev`·`bars_horizontal`·`stacked_bars`·`pie`·`prev_from`이 여기 있음).
- 패널은 `locals`의 `*_queries` 맵(키 순서 = id) + `*_rows`, 선택 속성은 `try(...)`로 읽는다. 쿼리는 grafana 스키마 뷰만 쓴다.
- 폴더 권한은 `folder.tf`가 전부 정한다: `Q-Story 운영·영업`(Viewer 열람) / `qstory-backend`(Editor 이상, 아이 발화 원문·에러 로그).
