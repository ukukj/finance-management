#!/usr/bin/env bash
set -euo pipefail

mode=${1:?"사용: leak-check.sh staged | range <git log 범위 인자...>"}
shift
root=$(git rev-parse --show-toplevel)
main_root=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
denylist=${LEAK_DENYLIST:-$HOME/.config/investment-dashboard/leak-denylist}
probe_dir=${LEAK_PROBE_DIR:-$main_root/.toss-probe}
binary_allow="$root/.leak-check-binary-allow"

if [[ ! -f "$denylist" ]]; then
    echo "leak-check: 금지어 목록이 없습니다: $denylist" >&2
    echo "leak-check: 실제 IP·이메일·계좌번호 등을 한 줄에 하나씩 적어 만드세요." >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

tr -d '\r' <"$denylist" | LC_ALL=C awk '
    { gsub("\302\240", " "); gsub(/^[ \t]+|[ \t]+$/, "") }
    $0 != "" && $0 !~ /^#/ { printf "%d\t%s\n", NR, $0 }
' >"$work/deny"
if [[ ! -s "$work/deny" ]]; then
    echo "leak-check: 금지어 목록에 항목이 없습니다: $denylist" >&2
    exit 1
fi

g() {
    git -c core.quotePath=false "$@"
}

added_lines() {
    awk '
        /^commit [0-9a-f]+$/ { commit = substr($2, 1, 7) ":"; header = 0; next }
        /^diff --git / { header = 1; file = "?"; next }
        header && /^\+\+\+ / { file = substr($0, 5); sub(/^b\//, "", file); next }
        /^@@ / { split($3, a, ","); line = substr(a[1], 2) + 0; header = 0; next }
        header { next }
        /^\+/ { printf "%s%s:%d\t%s\n", commit, file, line, substr($0, 2); line++ }
    '
}

new_paths() {
    awk '
        /^commit [0-9a-f]+$/ { commit = substr($2, 1, 7) ":"; next }
        NF { printf "%s새 경로 #%d\t%s\n", commit, ++n, $0 }
    '
}

binary_paths() {
    awk -F'\t' '
        /^commit [0-9a-f]+$/ { commit = substr($0, 8, 7) ":"; next }
        $1 == "-" && $2 == "-" { printf "%s%s\t%s\n", commit, $3, $3 }
    '
}

: >"$work/identities"
case "$mode" in
    staged)
        g diff --cached -U0 --no-color --no-ext-diff --no-textconv | added_lines >"$work/added"
        g diff --cached --name-only --diff-filter=d | new_paths >>"$work/added"
        g diff --cached --numstat --no-renames --diff-filter=d | binary_paths >"$work/binary"
        ;;
    range)
        log=(log --diff-merges=remerge --format='commit %H')
        g "${log[@]}" -p -U0 --no-color --no-ext-diff --no-textconv "$@" | added_lines >"$work/added"
        g "${log[@]}" --name-only --diff-filter=d "$@" | new_paths >>"$work/added"
        g "${log[@]}" --numstat --no-renames --diff-filter=d "$@" | binary_paths >"$work/binary"
        g log --format='commit %H%n%B' "$@" | awk '
            /^commit [0-9a-f]+$/ { commit = substr($2, 1, 7); next }
            { printf "%s:커밋 메시지\t%s\n", commit, $0 }
        ' >>"$work/added"
        g log --format='%h:작성자·커미터 이름%x09%an / %cn' "$@" >>"$work/added"
        g log --format='%h:작성자 이메일%x09%ae%n%h:커미터 이메일%x09%ce' "$@" >"$work/identities"
        ;;
    *)
        echo "leak-check: 알 수 없는 모드 $mode" >&2
        exit 2
        ;;
esac

if [[ -f "$binary_allow" ]]; then
    while IFS=$'\t' read -r where path; do
        allowed=false
        while IFS= read -r pattern || [[ -n "$pattern" ]]; do
            if [[ -n "$pattern" && "$pattern" != \#* && "$path" == $pattern ]]; then
                allowed=true
            fi
        done <"$binary_allow"
        if [[ "$allowed" == false ]]; then
            printf '%s\t%s\n' "$where" "$path"
        fi
    done <"$work/binary" >"$work/binary-checked"
    mv "$work/binary-checked" "$work/binary"
fi

: >"$work/probe"
if compgen -G "$probe_dir/*.json" >/dev/null; then
    jq -r '
        paths(scalars) as $p
        | ($p | map(select(type == "string")) | last) as $key
        | getpath($p) as $v
        | select($v != null and ($v | type) != "boolean")
        | [(if ($key // "" | test("^[a-z][A-Za-z0-9]*$")) then $key else "?" end), ($v | tostring)] | @tsv
    ' "$probe_dir"/*.json | sort -u >"$work/probe"
else
    echo "leak-check: 실제 토스 응답이 없어 비교를 건너뜁니다: $probe_dir" >&2
fi

LC_ALL=C awk -F'\t' '
    FILENAME == ARGV[1] { denies++; deny_line[denies] = $1; deny[denies] = tolower(substr($0, length($1) + 2)); next }
    FILENAME == ARGV[2] {
        key = $1; v = $2
        if (v ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]$/) next
        if (v ~ /^[A-Z][A-Z_]*$/ && key != "symbol" && key != "name" && key != "isinCode") next
        if (v ~ /^-?[0-9]+(\.[0-9]+)?$/) {
            digits = v; gsub(/[^0-9]/, "", digits)
            if (length(digits) < 5 || v ~ /000(\.0+)?$/) next
            num[v] = key
        } else if (length(v) >= 3) {
            str[v] = key
        }
        next
    }
    FILENAME == ARGV[3] {
        if ($2 !~ /(@users\.noreply\.github\.com|^noreply@github\.com)$/) report($1, "GitHub noreply 가 아닌 이메일")
        next
    }
    FILENAME == ARGV[4] { report($1, "바이너리 파일이라 내용을 검사할 수 없음"); next }
    { scan(substr($0, length($1) + 2), $1, 0) }

    function scan(text, where, dry,    lower, i, v, s, ip, mail) {
        lower = tolower(text); gsub("\302\240", " ", lower)
        for (i = 1; i <= denies; i++) if (index(lower, deny[i])) { if (dry) return 1; report(where, "금지어 목록 " deny_line[i] "번째 줄") }
        for (v in str) if (has_string(text, v)) { if (dry) return 1; report(where, "실제 토스 응답 값과 일치 (키 " str[v] ")") }
        for (v in num) if (has_number(text, v)) { if (dry) return 1; report(where, "실제 토스 응답 값과 일치 (키 " num[v] ")") }
        s = text
        while (match(s, /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/)) {
            ip = substr(s, RSTART, RLENGTH)
            if (ip !~ /^(127\.0\.0\.1|0\.0\.0\.0|192\.0\.2\.[0-9]+|198\.51\.100\.[0-9]+|203\.0\.113\.[0-9]+)$/) {
                if (dry) return 1
                report(where, "문서용 대역이 아닌 IPv4 주소")
            }
            s = substr(s, RSTART + RLENGTH)
        }
        s = text
        while (match(s, /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z][A-Za-z]+/)) {
            mail = substr(s, RSTART, RLENGTH)
            if (mail !~ /^(this|super)@/ &&
                mail !~ /(@users\.noreply\.github\.com|@example\.(com|org|net)|^noreply@[A-Za-z0-9.-]+)$/) {
                if (dry) return 1
                report(where, "이메일 주소")
            }
            s = substr(s, RSTART + RLENGTH)
        }
        if (text ~ /"accountNo"[[:space:]]*:[[:space:]]*"[0-9-]*[1-9][0-9-]*"/) {
            if (dry) return 1
            report(where, "마스킹되지 않은 accountNo")
        }
        return 0
    }
    function has_string(s, v,    i, before, after) {
        if (v !~ /^[A-Za-z0-9_.-]+$/) return index(s, v) > 0
        while ((i = index(s, v)) > 0) {
            before = substr(s, i - 1, 1); after = substr(s, i + length(v), 1)
            if (before !~ /[A-Za-z0-9_]/ && after !~ /[A-Za-z0-9_]/) return 1
            s = substr(s, i + length(v))
        }
        return 0
    }
    function has_number(s, v,    i, before, after) {
        while ((i = index(s, v)) > 0) {
            before = substr(s, i - 1, 1); after = substr(s, i + length(v), 2)
            if (before !~ /[0-9.]/ && after !~ /^([0-9]|\.[0-9])/) return 1
            s = substr(s, i + length(v))
        }
        return 0
    }
    function report(where, why) {
        found[++count] = where; reason[count] = why
    }
    END {
        for (k = 1; k <= count; k++) {
            where = scan(found[k], "", 1) ? "(값이 든 위치라 가림)" : found[k]
            line = where ": " reason[k]
            if (!(line in seen)) { seen[line] = 1; print line }
        }
        exit (count > 0 ? 1 : 0)
    }
' "$work/deny" "$work/probe" "$work/identities" "$work/binary" "$work/added" >"$work/found" || {
    echo "leak-check: 저장소에 들어가면 안 되는 값이 있습니다 ($mode)." >&2
    cat "$work/found" >&2
    exit 1
}
