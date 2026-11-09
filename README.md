# data

HUD Launcher の TASKS パネルが読み込むデータ置き場です。

`tasks-morning.enc` と `tasks-weekly.enc` は AES-256-GCM で暗号化されており、合言葉がなければ読めません。
このブランチへの push ではアプリのビルドは走りません。
