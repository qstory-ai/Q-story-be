# develop 중심 개발과 main 체리픽

## 상태

채택

## 맥락

- 지금까지는 작업 브랜치를 main에 바로 머지했다. main은 배포 서버(demo.api.qstory.ai.kr)에 자동 배포된다.
- 그래서 확인하지 않은 변경이 바로 운영에 나갔다. 2026-11-22에는 #112와 #118이 마이그레이션 번호 102를 같이 써서 main 배포가 계속 롤백됐다(#127에서 고침).
- 같은 날 develop 배포 경로를 만들었다. develop은 개발 서버(dev.api.qstory.ai.kr)에 배포되고, 웹은 dev.qstory.ai.kr이 develop을 따른다.

## 결정

- 작업 브랜치는 develop에서 따고 develop으로 PR을 연다.
- develop에 머지하면 개발 서버에 배포된다. 여기서 확인한다.
- 확인이 끝난 커밋만 main에 체리픽한다. develop을 main에 통째로 머지하지 않는다.
  1. `git switch -c release/<제목> origin/main`
  2. `git cherry-pick -x <develop 커밋>`. `-x`를 붙이면 원래 커밋이 메시지에 남는다.
  3. main으로 PR을 열고 CI를 통과하면 머지한다. 배포 서버에 배포된다.
- 운영 긴급 수정은 main에서 따서 고치고 main에 머지한다. 그다음 같은 커밋을 develop에도 체리픽한다.
- 모든 PR에 docs/adr 기록을 남긴다. 규칙은 docs/adr/README.md.

## 작업 내용

- `docs/adr/README.md`: 기록 규칙. 파일 이름은 날짜로 하고 번호는 쓰지 않는다.
- `docs/adr/_template.md`: 기록 양식. 상태, 맥락, 결정, 작업 내용, 확인 방법, 영향.
- `.github/workflows/adr-check.yml`: PR에 docs/adr 기록이 없거나 필수 항목이 빠지면 실패한다. 배포 판단은 ci 워크플로의 test 결과만 보므로 배포에는 영향이 없다.
- `.github/pull_request_template.md`: PR 본문에 ADR 파일과 확인 내용을 적는 칸.
- `.gitignore`: `*.md` 규칙은 그대로 두고 `docs/` 아래 md와 PR 템플릿만 예외로 올린다.
- 개발 서버는 2026-11-22부터 `/opt/qstory/.env`의 `GIT_REF=develop`으로 develop을 따른다.

- GitHub 저장소 기본 브랜치를 main에서 develop으로 바꿨다(`gh repo edit --default-branch develop`). 서버는 `.env`의 `GIT_REF`로, Vercel은 프로젝트의 Production Branch(main)로 배포 브랜치를 정한다. 그래서 배포 대상은 바뀌지 않는다.

## 확인 방법

- `git check-ignore`로 docs/adr 파일과 PR 템플릿이 더 이상 무시되지 않는 것을 확인했다.
- 이 PR에서 adr 검사가 이 파일을 찾아 통과하는지 본다.

## 영향

- 이제 develop을 main에 맞춰 되돌리지 않는다. 체리픽하면 main과 develop의 커밋 해시가 달라진다. 내용은 같다.
- 아직 main에 안 간 커밋은 `git cherry -v origin/main origin/develop`으로 본다. `+`는 main에 없는 커밋이고, `-`는 같은 변경이 이미 main에 있는 커밋이다.
- 마이그레이션은 번호 순서대로 main에 옮긴다. Flyway는 out-of-order가 꺼져 있다. 운영 DB에 105가 먼저 들어간 뒤 104가 오면 검증에 걸려 앱이 뜨지 않는다. 앞 번호가 아직 확인 전이면 뒤 번호도 같이 기다린다.
- 서로 얽힌 커밋 하나만 골라 옮기면 충돌하거나 빌드가 깨질 수 있다. 체리픽 PR도 CI를 통과해야 머지한다.
- GitHub 기본 브랜치가 develop이라서 새 PR의 base는 develop으로 잡힌다. main으로 가는 체리픽 PR만 base를 main으로 바꾼다.
