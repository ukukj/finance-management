#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${TOSS_CLIENT_ID:-}" || -z "${TOSS_CLIENT_SECRET:-}" ]]; then
    echo "TOSS_CLIENT_ID, TOSS_CLIENT_SECRET 환경변수가 필요합니다." >&2
    exit 1
fi

base_url="${TOSS_BASE_URL:-https://openapi.tossinvest.com}"
out_dir="$(git rev-parse --show-toplevel)/.toss-probe"
mkdir -p "$out_dir"

uri_encode() {
    jq -rR '@uri'
}

mask_account_no() {
    jq 'walk(if type == "object" and has("accountNo") then .accountNo = "00000000000" else . end)'
}

token_body="grant_type=client_credentials&client_id=$(printf '%s' "$TOSS_CLIENT_ID" | uri_encode)&client_secret=$(printf '%s' "$TOSS_CLIENT_SECRET" | uri_encode)"
token_response=$(printf '%s' "$token_body" | curl -sS --fail-with-body -X POST "$base_url/oauth2/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data @-) || {
    echo "토큰 발급 실패: $token_response" >&2
    exit 1
}
token=$(printf '%s' "$token_response" | jq -r '.access_token // empty')
unset token_body token_response
if [[ -z "$token" ]]; then
    echo "토큰 응답에 access_token 이 없습니다." >&2
    exit 1
fi

api_get() {
    local path=$1
    local account_seq=${2:-}
    local headers="Authorization: Bearer $token"
    if [[ -n "$account_seq" ]]; then
        headers+=$'\n'"X-Tossinvest-Account: $account_seq"
    fi
    curl -sS --fail-with-body "$base_url$path" -H @<(printf '%s\n' "$headers")
}

save() {
    local path=$1
    local account_seq=$2
    local file=$3
    local body
    body=$(api_get "$path" "$account_seq") || {
        echo "요청 실패 $path: $body" >&2
        exit 1
    }
    printf '%s' "$body" | mask_account_no >"$file"
}

save /api/v1/accounts "" "$out_dir/accounts.json"
account_seq=$(jq -r '[.result[] | select(.accountType == "BROKERAGE") | .accountSeq] | first // empty' "$out_dir/accounts.json")
if [[ -z "$account_seq" ]]; then
    echo "BROKERAGE 계좌가 없습니다." >&2
    exit 1
fi
sleep 1

save /api/v1/holdings "$account_seq" "$out_dir/holdings.json"
sleep 0.3

rm -f "$out_dir"/transactions-p*.json
page=1
cursor=""
while :; do
    query="limit=100"
    if [[ -n "$cursor" ]]; then
        query+="&cursor=$(printf '%s' "$cursor" | uri_encode)"
    fi
    save "/api/v1/transactions?$query" "$account_seq" "$out_dir/transactions-p$page.json"
    if [[ "$(jq -r '.result.hasNext' "$out_dir/transactions-p$page.json")" != "true" ]]; then
        break
    fi
    cursor=$(jq -r '.result.nextCursor' "$out_dir/transactions-p$page.json")
    page=$((page + 1))
    sleep 0.3
done
sleep 0.3

save "/api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW" "" "$out_dir/exchange-rate.json"

echo "저장: $out_dir"
echo "계좌 $(jq '.result | length' "$out_dir/accounts.json")개, 보유종목 $(jq '.result.items | length' "$out_dir/holdings.json")개, 거래내역 ${page}쪽 $(jq -s 'map(.result.transactions | length) | add' "$out_dir"/transactions-p*.json)행"
