#!/usr/bin/env bash
set -uo pipefail

src=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
passed=0
failed=0
at=@
dot=.
acct=accountNo

ok() {
    passed=$((passed + 1))
    echo "ok    $1"
}

fail() {
    failed=$((failed + 1))
    echo "FAIL  $1"
    [[ -f "$tmp/err" ]] && sed 's/^/      /' "$tmp/err"
}

cd "$tmp"
git init -q -b main work
cd work
git config user.name tester
git config user.email 1+tester@users.noreply.github.com
mkdir -p scripts .githooks
cp "$src/scripts/leak-check.sh" scripts/
cp "$src/.githooks/pre-commit" "$src/.githooks/pre-push" .githooks/
cp "$src/.leak-check-binary-allow" .
printf '.toss-probe/\n' >.gitignore
printf '*.dat -diff\n' >.gitattributes
git add -A
git commit -qm init --no-verify
git config core.hooksPath .githooks

printf '# fake\nFakeCorp\nTrailSpace   \nCrlfWord\r\n\nNbspWord\302\240\nHong\302\240Gildong\n' >"$tmp/deny"
mkdir "$tmp/probe"
cat >"$tmp/probe/p.json" <<'JSON'
{"result": {"items": [
  {"symbol": "777888", "name": "테스트전자", "quantity": "123456", "amount": "5000000",
   "transactionType": "DIVIDEND", "rateChangeType": "DOWN", "currency": "KRW",
   "settlementDate": "2025-01-02", "transactionTime": "2025-01-02T10:11:12.345+09:00",
   "accountNo": "00000000000"},
  {"symbol": "ABC", "name": "x", "orderId": "AB12345678"}],
 "balances": {"99887766554": "5234567"},
 "baseCurrency": "USD", "quoteCurrency": "KRW"}}
JSON
export LEAK_DENYLIST="$tmp/deny" LEAK_PROBE_DIR="$tmp/probe"

commit_case() {
    local want=$1 label=$2 path=$3 content=$4 reason=$5
    mkdir -p "$(dirname "$path")"
    if [[ "${6:-}" == binary ]]; then
        printf 'a\000%s\000b' "$content" >"$path"
    else
        printf '%s\n' "$content" >"$path"
    fi
    git add -- "$path"
    if git commit -qm case 2>"$tmp/err"; then
        got=allow
        git reset -q --soft HEAD~1
    else
        got=block
    fi
    git rm -rq --cached -- "$path" >/dev/null 2>&1
    rm -rf -- "$path"
    if [[ "$got" != "$want" ]]; then
        fail "$want: $label (got $got)"
    elif [[ "$want" == block ]] && ! grep -qF -- "$reason" "$tmp/err"; then
        fail "$want: $label (사유 '$reason' 없음)"
    else
        ok "$want: $label"
    fi
}

commit_case block "금지어 대소문자 무시" case.txt "client = fakecorp" "금지어 목록 2번째 줄"
commit_case block "금지어 끝 공백" case.txt "x TrailSpace" "금지어 목록 3번째 줄"
commit_case block "금지어 CRLF 줄" case.txt "x CrlfWord y" "금지어 목록 4번째 줄"
commit_case block "금지어 끝 줄바꿈 없는 공백(NBSP)" case.txt "x NbspWord y" "금지어 목록 6번째 줄"
commit_case block "금지어 안의 NBSP" case.txt "name: Hong Gildong" "금지어 목록 7번째 줄"
commit_case block "실제 응답 대문자·숫자 ID" case.txt 'val order = "AB12345678"' "실제 토스 응답 값과 일치 (키 orderId)"
commit_case block "실제 응답 키가 값인 경우 키를 가림" case.txt 'val v = "5234567"' "실제 토스 응답 값과 일치 (키 ?)"
commit_case block "실제 응답 종목 코드" case.txt 'val s = "777888"' "실제 토스 응답 값과 일치 (키 symbol)"
commit_case block "실제 응답 한글 종목명" case.txt "name = 테스트전자" "실제 토스 응답 값과 일치 (키 name)"
commit_case block "실제 응답 수량" case.txt "qty 123456 end" "실제 토스 응답 값과 일치 (키 quantity)"
commit_case block "실제 응답 거래 시각" case.txt "t=2025-01-02T10:11:12.345+09:00" "실제 토스 응답 값과 일치 (키 transactionTime)"
commit_case block "실제 응답 짧은 티커 단독" case.txt "buy ABC now" "실제 토스 응답 값과 일치 (키 symbol)"
commit_case block "compose 호스트 매핑" case.yaml "      - \"nas:192${dot}168${dot}0${dot}10\"" "문서용 대역이 아닌 IPv4 주소"
commit_case block "docker --add-host" case.txt "docker run --add-host=nas:10${dot}20${dot}30${dot}40 x" "문서용 대역이 아닌 IPv4 주소"
commit_case block "ssh 터널 host:port:IP" case.txt "ssh -N -L localhost:5432:192${dot}168${dot}0${dot}10:5432 nas" "문서용 대역이 아닌 IPv4 주소"
commit_case block "IPv4 매핑 IPv6 풀어 쓴 형태" case.txt "0:0:0:0:0:ffff:10${dot}20${dot}30${dot}40" "문서용 대역이 아닌 IPv4 주소"
commit_case block "IPv4 매핑 IPv6" case.txt "\"::ffff:10${dot}20${dot}30${dot}40\"" "문서용 대역이 아닌 IPv4 주소"
commit_case block "문서용이 아닌 IPv4" case.txt "host 8${dot}8${dot}4${dot}4" "문서용 대역이 아닌 IPv4 주소"
commit_case block "이메일" case.txt "mail someone${at}company.co.kr" "이메일 주소"
commit_case block "마스킹 안 된 accountNo" case.json "{\"$acct\": \"12345678901\"}" "마스킹되지 않은 accountNo"
commit_case block "++ 로 시작하는 줄" case.txt "++ FakeCorp" "case.txt:1: 금지어 목록 2번째 줄"
commit_case block "경로에만 든 값" "d/FakeCorp-note.txt" "plain" "새 경로 #1: 금지어 목록 2번째 줄"
commit_case block "바이너리 파일" case.bin "plain" "바이너리 파일이라 내용을 검사할 수 없음" binary
commit_case block "diff 를 끈 텍스트 파일" case.dat "FakeCorp" "바이너리 파일이라 내용을 검사할 수 없음"
commit_case allow "문서용 IPv4" case.txt "ip 203.0.113.7 198.51.100.2 127.0.0.1" -
commit_case allow "noreply·example 이메일" case.txt "<noreply@anthropic.com> 1+x@users.noreply.github.com a@example.com" -
commit_case allow "마스킹된 accountNo" case.json '{"accountNo": "00000000000"}' -
commit_case allow "끝이 000 인 금액" case.txt "amount 5000000" -
commit_case allow "날짜만 같은 값" case.txt "date 2025-01-02" -
commit_case allow "대문자 enum 값" case.txt "DIVIDEND KRW USD RoundingMode.DOWN" -
commit_case allow "다른 수" case.txt "1234567 0.123456 123456.5" -
commit_case allow "짧은 티커가 단어 일부" case.txt "ABCD xABC ABC_1" -
commit_case allow "버전 문자열" case.txt "gradle 9.9.2 kotlin 2.4.21" -
commit_case allow "Kotlin 레이블 this@" case.txt "this@TossClient.baseUrl" -
commit_case allow "허용한 바이너리" backend/gradle/wrapper/gradle-wrapper.jar "plain" - binary

printf 'x fakecorp 777888\nv = 5234567\n' >"d-777888.txt"
git add d-777888.txt
git commit -qm leak 2>"$tmp/err"
git rm -q --cached d-777888.txt
rm -f d-777888.txt
if grep -qiE 'fakecorp|777888|99887766554' "$tmp/err"; then fail "출력에 찾은 값이 없다"; else ok "출력에 찾은 값이 없다"; fi

cp "$tmp/deny" "$tmp/deny.bak"
printf '# only comments\n\n' >"$tmp/deny"
commit_case block "주석뿐인 금지어 목록" case.txt "plain" "금지어 목록에 항목이 없습니다"
rm -f "$tmp/deny"
commit_case block "금지어 목록 없음" case.txt "plain" "금지어 목록이 없습니다"
mv "$tmp/deny.bak" "$tmp/deny"

mkdir -p .toss-probe
cp "$tmp/probe/p.json" .toss-probe/
git worktree add -q -b wt "$tmp/wt" 2>/dev/null
(
    cd "$tmp/wt"
    unset LEAK_PROBE_DIR
    printf 'val s = "777888"\n' >wt.txt
    git add wt.txt
    if git commit -qm wt 2>"$tmp/err"; then exit 1; fi
    grep -qF "실제 토스 응답 값과 일치 (키 symbol)" "$tmp/err"
) && ok "block: 연결 워크트리에서도 실제 응답 비교" || fail "block: 연결 워크트리에서도 실제 응답 비교"

git init -q --bare -b main "$tmp/remote.git"
git remote add origin "$tmp/remote.git"
push_case() {
    local want=$1 label=$2 reason=${3:-}
    if git push -q origin HEAD:main 2>"$tmp/err"; then got=allow; else got=block; fi
    if [[ "$got" != "$want" ]]; then
        fail "push $want: $label (got $got)"
    elif [[ "$want" == block ]] && ! grep -qF -- "$reason" "$tmp/err"; then
        fail "push $want: $label (사유 '$reason' 없음)"
    else
        ok "push $want: $label"
    fi
}

push_case allow "깨끗한 커밋"
printf 'FakeCorp\n' >a.txt && git add a.txt && git commit -qm bypass --no-verify
push_case block "훅을 건너뛴 커밋" "a.txt:1: 금지어 목록 2번째 줄"
git reset -q --hard HEAD~1
printf 'b\n' >b.txt && git add b.txt && git -c user.email="dev${at}company.co.kr" commit -qm author --no-verify
push_case block "noreply 가 아닌 작성자" "작성자 이메일: GitHub noreply 가 아닌 이메일"
git reset -q --hard HEAD~1
printf 'c\n' >c.txt && git add c.txt && git commit -qm "contact someone${at}company.co.kr" --no-verify
push_case block "커밋 메시지의 이메일" "커밋 메시지: 이메일 주소"
git reset -q --hard HEAD~1
git checkout -q -b side
printf 'side\n' >m.txt && git add m.txt && git commit -qm side
git checkout -q main
printf 'main\n' >m.txt && git add m.txt && git commit -qm main
git merge -q side >/dev/null 2>&1
printf 'resolved FakeCorp\n' >m.txt && git add m.txt && git commit -qm merge --no-verify
push_case block "머지 커밋의 충돌 해결 내용" "m.txt:1: 금지어 목록 2번째 줄"
git reset -q --hard HEAD~2
git clone -q "$tmp/remote.git" "$tmp/other" 2>/dev/null
(
    cd "$tmp/other"
    git config user.name other
    git config user.email 2+other@users.noreply.github.com
    printf 'other\n' >o.txt && git add o.txt && git commit -qm other --no-verify && git push -q origin HEAD:main
) || fail "준비: 다른 클론에서 원격에 커밋 올리기"
if git cat-file -e "$(git ls-remote "$tmp/remote.git" main | cut -f1)" 2>/dev/null; then
    fail "준비: 원격 main 이 로컬에 없는 커밋이어야 함"
fi
printf 'd\n' >d.txt && git add d.txt && git commit -qm d
if git push -q -f origin HEAD:main 2>"$tmp/err"; then
    ok "push allow: 로컬에 없는 원격 sha 를 강제 push 로 덮기"
else
    fail "push allow: 로컬에 없는 원격 sha 를 강제 push 로 덮기"
fi

echo "passed=$passed failed=$failed"
[[ "$failed" -eq 0 ]]
