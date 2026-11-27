# 백엔드

배당 대시보드 서버. 애플리케이션 하나를 두 모드로 실행한다.

- API 모드(기본): REST API. 시작할 때 Flyway 마이그레이션을 적용한다. 기본 로컬 런처에서는 `POST /api/sync` 가 이 프로세스 안에서 토스 동기화를 돌리므로 토스 키와 허용 IP 가 필요하다. 다른 API 는 토스를 부르지 않는다.
- sync 모드(`sync` 프로필): 토스 동기화를 한 번 돌리고 종료 코드를 남기고 끝난다. 웹 서버와 마이그레이션이 없다.

명령은 모두 `backend/` 에서 실행한다. JDK 21(Gradle 툴체인)과 Docker 가 필요하다.

## 로컬 DB

```sh
docker compose up -d
```

`postgres:17` 컨테이너가 `127.0.0.1:5432` 에 뜬다. DB 이름·사용자·비밀번호는 모두 `investment` 이고 `application-local.yml` 이 이 값을 쓴다. 데이터는 볼륨 `investment-dashboard_postgres-data` 에 남는다.

## 환경변수

| 이름 | 모드 | 뜻 |
| --- | --- | --- |
| `TOSS_CLIENT_ID`, `TOSS_CLIENT_SECRET` | sync, API(`POST /api/sync`) | 토스 Open API 키. 토스에 등록한 허용 IP 에서 실행해야 한다. 없으면 토스를 부르지 않고 동기화 기록이 FAILED `동기화 실패: 토스 키 없음` 으로 남는다. |
| `APP_LOGIN_USERNAME` | API | 로그인 아이디 |
| `APP_LOGIN_PASSWORD_HASH` | API | 로그인 비밀번호의 BCrypt 해시 |
| `APP_JWT_SECRET` | API | JWT 서명 키. base64, 풀었을 때 32바이트 이상 |
| `SYNC_TRIGGER` | sync | 실행 계기. 없으면 `SCHEDULED`, `MANUAL` 도 받는다. 그 밖의 값이면 기동에 실패한다. |

로그인 값 셋 중 하나라도 없거나 형식이 틀리면 API 모드는 뜨지 않는다. 예를 들어 셋 다 없으면 `APP_LOGIN_USERNAME 없음` 으로 기동에 실패한다. sync 모드는 로그인 값이 없어도 뜬다.

비밀번호 해시와 서명 키는 이렇게 만든다.

```sh
export APP_LOGIN_USERNAME='<아이디>'
export APP_LOGIN_PASSWORD_HASH="$(docker run --rm httpd:2.4-alpine htpasswd -nbBC 12 x '<비밀번호>' | cut -d: -f2)"
export APP_JWT_SECRET="$(openssl rand -base64 32)"
```

- `htpasswd` 는 `$2y$12$` 로 시작하는 60자 해시를 낸다. Spring 의 BCrypt 는 `$2y$` 접두를 받는다.
- 해시를 직접 붙여 넣을 때는 `$` 가 셸 변수로 풀리지 않게 작은따옴표로 감싼다.
- `APP_JWT_SECRET` 을 바꾸면 이미 발급한 토큰이 모두 무효가 되어 다시 로그인해야 한다. 한 번 만든 값을 계속 쓴다.

## API 모드

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
```

빈 DB 에서 띄우면 마이그레이션 V1~V6 을 적용하고 8080 포트에서 뜬다.

```sh
curl -sS http://localhost:8080/actuator/health
```

응답은 `{"groups":["liveness","readiness"],"status":"UP"}` 이다. `/api/auth/login`·`refresh`·`logout` 말고 `/api` 아래는 Bearer 토큰이 필요하다.

로그인해서 받은 access token 으로 수동 동기화를 부른다. access token 은 30분 동안 쓸 수 있다.

```sh
TOKEN=$(curl -sS -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"<아이디>","password":"<비밀번호>"}' | jq -r .accessToken)
curl -sS -i -X POST http://localhost:8080/api/sync -H "Authorization: Bearer $TOKEN"
curl -sS http://localhost:8080/api/sync/latest -H "Authorization: Bearer $TOKEN"
```

- `POST /api/sync` 는 동기화를 기다리지 않고 본문 없는 202 를 준다. 동기화는 앱 안의 `sync-1` 스레드에서 돈다. 토큰이 없으면 401 이다.
- 동기화가 도는 중에 다시 부르면 끝난 뒤 한 번 더 돈다. 이미 하나가 기다리는 중이면 그 요청은 버린다(응답은 그대로 202).
- 앱을 멈출 때 도는 동기화는 최대 30초 기다리고, 기다리던 동기화는 버린다.
- `GET /api/sync/latest` 는 마지막 실행의 상태·시작 시각·사유를 준다. 시각은 `+09:00` 이다. 토스 키 없이 실행했다면 `{"lastSucceededAt":null,"lastStatus":"FAILED","lastStartedAt":"2026-11-27T21:05:12.345678+09:00","lastFailureReason":"동기화 실패: 토스 키 없음"}` 꼴이다. `lastStartedAt` 값은 실행한 시각에 따라 다르다.

## sync 모드

```sh
./gradlew bootRun --args='--spring.profiles.active=local,sync'
echo $?
```

- 스키마는 API 모드가 만든다. 빈 DB 라면 API 모드를 먼저 한 번 띄운다.
- 동기화를 한 번 돌리고 결과로 종료 코드를 정한다. SUCCEEDED·SKIPPED 는 0, FAILED 는 1 이다. 예상하지 못한 오류가 나면 기동 실패로 1 이다.
- 종료 코드가 0 이 아니면 Gradle 도 `finished with non-zero exit value 1` 과 `BUILD FAILED` 를 내고 1 로 끝난다.
- 토스 키 없이 실행하면 `sync_run` 에 SCHEDULED·FAILED·`동기화 실패: 토스 키 없음` 이 남고 종료 코드는 1 이다.
- `SYNC_TRIGGER=MANUAL` 을 앞에 붙이면 MANUAL 로 기록한다. `SYNC_TRIGGER=manual` 처럼 다른 값이면 `SYNC_TRIGGER 는 SCHEDULED 또는 MANUAL: manual` 로 기동에 실패하고 동기화 기록은 생기지 않는다.

마지막 실행 기록은 DB 에서 바로 볼 수 있다.

```sh
docker compose exec -T postgres psql -U investment -d investment \
  -c "select id, trigger_type, status, failure_reason from sync_run order by id desc limit 1"
```

## 토스 API 확인 스크립트

`scripts/toss-probe.sh` 는 토스 Open API 를 직접 불러 응답을 파일로 남긴다. 토큰 발급(POST)과 조회(GET)만 한다. 토스에 등록한 허용 IP 에서 `curl` 과 `jq` 로 실행한다. 파일에 실행 권한이 없어 `bash` 로 부른다.

```sh
TOSS_CLIENT_ID='<client id>' TOSS_CLIENT_SECRET='<client secret>' bash ../scripts/toss-probe.sh
```

- 저장 위치는 저장소 루트의 `.toss-probe/` 다. git 이 무시하는 폴더이고 실제 계좌 데이터가 들어 있으니 다른 곳으로 옮기지 않는다.
- `accounts.json`, `holdings.json`, `transactions-p{쪽}.json`, `exchange-rate.json` 을 남기고 계좌번호는 `00000000000` 으로 가린다. 끝에 계좌·보유종목·거래내역 수를 출력한다.
- `TOSS_BASE_URL` 로 기본 주소 `https://openapi.tossinvest.com` 을 바꿀 수 있다.
- 키가 없으면 `TOSS_CLIENT_ID, TOSS_CLIENT_SECRET 환경변수가 필요합니다.` 를 내고 1 로 끝난다.

## 검사

```sh
./gradlew check
```

ktlint, detekt, ArchUnit, 테스트를 돌린다. 테스트는 Testcontainers 로 PostgreSQL 컨테이너를 띄우므로 Docker 가 켜져 있어야 한다.
