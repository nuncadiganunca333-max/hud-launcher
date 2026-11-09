#!/usr/bin/env python3
"""HUD Launcher の TASKS パネル用に、まとめ(JSON)を暗号化して保存する。

使い方:  HUD_KEY=<合言葉> python3 push_tasks.py morning tasks.json
         HUD_KEY=<合言葉> python3 push_tasks.py weekly  tasks.json

tasks.json の形:
{
  "headline": "ひとことで今日の全体像",
  "tasks": [ {"t": "やること(40字以内)", "p": 1, "from": "相手や案件", "due": "期限(あれば)"} ]
}
p は 1=急ぎ 2=今日中 3=今週中（weekly では無視される）。
出力は base64( nonce(12) + AES-256-GCM の暗号文とタグ ) で、tasks-<kind>.enc に書く。
"""
import base64, datetime, json, os, sys

def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("morning", "weekly"):
        sys.exit(__doc__)
    kind, src = sys.argv[1], sys.argv[2]
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        sys.exit("cryptography が必要です:  pip install --break-system-packages cryptography")
    k = os.environ.get("HUD_KEY", "").strip()
    key = base64.urlsafe_b64decode(k + "=" * (-len(k) % 4))
    if len(key) != 32:
        sys.exit("HUD_KEY が正しくありません")
    data = json.load(open(src, encoding="utf-8"))
    tasks = []
    for t in data.get("tasks", [])[:30]:
        text = str(t.get("t", "")).strip()
        if not text:
            continue
        item = {"t": text[:80]}
        if kind == "morning":
            p = t.get("p", 2)
            item["p"] = p if p in (1, 2, 3) else 2
        for f in ("from", "due"):
            v = str(t.get(f, "") or "").strip()
            if v:
                item[f] = v[:40]
        tasks.append(item)
    jst = datetime.timezone(datetime.timedelta(hours=9))
    payload = {"v": 1, "kind": kind, "updated": datetime.datetime.now(jst).isoformat(timespec="seconds"),
               "headline": str(data.get("headline", ""))[:120], "tasks": tasks}
    nonce = os.urandom(12)
    ct = AESGCM(key).encrypt(nonce, json.dumps(payload, ensure_ascii=False).encode("utf-8"), None)
    out = "tasks-%s.enc" % kind
    with open(out, "w") as f:
        f.write(base64.b64encode(nonce + ct).decode() + "\n")
    print("wrote %s (%d tasks)" % (out, len(tasks)))

if __name__ == "__main__":
    main()
