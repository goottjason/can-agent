# 복권 구매 사이드카

Java(can-agent)가 subprocess로 호출한다. stdout 마지막 줄에 JSON을 출력한다.

## 설치
    python3 -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt
    playwright install chromium
    cp .env.example .env    # DH_USER / DH_PASSWORD 입력

## 사용
    python3 buy.py balance                         # 예치금만
    python3 buy.py buy --games LOTTO645,WIN720 --dry-run   # 구매 없이 로그인·잔액
    python3 buy.py buy --games LOTTO645,WIN720             # 실구매

## Java 연동
application.yml:
    lottery.sidecar-command: "python3,/절대경로/sidecar/lottery/buy.py"
크리덴셜은 이 디렉터리 .env 에만 둔다(커밋 금지). Java는 크리덴셜을 다루지 않는다.
