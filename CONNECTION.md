# Pythonとの接続テスト

「PC接続」は **全体画像の手動送信による通信確認**。「顔JPEG・認識設定」には顔の切り抜き確認、PCなしの仮応答テスト、顔ごとの自動送信とARプロフィール更新を実装している。正式な結合仕様は **API_CONTRACT.md（v1）** を参照する。

## Android側の操作

1. アプリを起動し、空間AR画面の「PC接続」を押す。通常カメラモードは廃止。
2. PCのURLを `http://192.168.1.10:8000` の形式で入力する。末尾の `/recognize` は付けない。URLは端末内に保存する。
3. 「接続テスト」で `GET /health` を確認する。
4. 「今のカメラ画像を取得」で画像を確認する。
5. 「このJPEGを1枚送信」を押し、応答JSONを確認する。

送信画像はカメラ画像全体。ARカードや検出枠は含めない。ARCoreのCPU画像を端末の向きに合わせて回転した画像を使う。最大辺960px、JPEG品質85にしてメモリ内で処理する。写真はファイル保存しない。

## Python側への取り決め案

以下は最初の手動接続テストの最小例。顔ごとの自動送信では、API_CONTRACT.mdに記載したschemaVersion・trackId・profileなども必須。

`GET /health`:

```json
{"status":"ok"}
```

`POST /recognize`: `multipart/form-data`

- `image`: JPEGファイル。ファイル名は `capture.jpg`。
- `requestId`: Androidが発行したUUID文字列。

応答例：

```json
{
  "requestId": "受け取った値をそのまま返す",
  "matched": true,
  "userId": "user_001",
  "similarity": 0.82
}
```

一致しない場合は `matched: false`, `userId: null` を想定する。現在の画面は応答をそのまま表示するため、未登録者の応答や追加フィールドも確認できる。requestIdが含まれる場合は送信値との一致を確認する。

接続タイムアウト5秒、応答待ち15秒。HTTPエラーはステータスコードと本文を表示する。

## ネットワーク

Python APIを `0.0.0.0:8000` で起動し、スマホとPCを端末間通信が許可された同じWi-Fiにつなぐ。Android側で指定するのはPCのIPv4アドレス。`0.0.0.0`、`127.0.0.1`、`localhost`はスマホからの送信先にしない。

この開発版は同一LANのHTTP接続用にINTERNET権限と平文通信許可を追加している。インターネット公開を行う場合はHTTPSと認証を別途設計する。

## 確認の範囲

ビルド・ローカルHTTPサーバーを使ったクライアント単体テストは実行可能。担当PCとの通信、カメラJPEGの向きと色、空間AR中の画像取得は実機で確認する。
