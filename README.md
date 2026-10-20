# Q-story-be

Q-Story 서비스의 백엔드 서버입니다.

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 3.5 (Web, Data JPA, Security, WebSocket) |
| 데이터베이스 | PostgreSQL, Flyway 마이그레이션 |
| 인증 | Spring Security + JWT (jjwt) |
| API 문서 | springdoc-openapi (Swagger UI) |
| 로깅·모니터링 | Logback + Loki appender, 모니터링 알림 설정은 `ops/`에서 코드로 관리 |
| 빌드·배포 | Gradle, Docker, GitHub Actions (테스트 CI, 배포 알림) |

## 구조

도메인별 패키지 구조입니다(`src/main/java/com/qstory/backend/<도메인>/{controller,service,repository,entity,dto}`).

- `story`, `storyadmin`, `storyreport`, `narration`: 이야기 콘텐츠와 낭독
- `playsession`, `interaction`, `companionchat`, `sttstream`: 플레이 세션, 실시간 상호작용, 음성 인식 스트림
- `identity`, `parent`, `org`, `institution`, `tutor`: 계정과 기관·보호자
- `payment`, `entitlement`, `notification`, `push`: 결제·이용권·알림
- `common`, `config`, `ratelimit`, `health`: 공통 오류 처리, 설정, 요청 제한, 헬스 체크

## 로컬 실행

```bash
cp .env.example .env   # 값 채우기
./gradlew bootRun
./gradlew test
```
