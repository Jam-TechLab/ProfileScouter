# ProfileScouter 認識API v1

AndroidとPythonの結合はこの仕様を基準にする。2026-10-30にAndroid側で先行定義。Python担当は顔認識方式やDBの内部構造を自由に実装してよい。

## 接続

- 同じWi-Fi内で担当PCのAPIへ接続する。
- ベースURL例：`http://192.168.1.10:8000`。スマホにはPCのIPv4を設定する。
- PCはAPIを`0.0.0.0:8000`で起動する。スマホから`localhost`や`0.0.0.0`へ送信しない。
- 本仕様は開発LAN用。インターネット公開やログイン・閲覧認可はまだ対象外。

## GET /health

パスは設定で変更可能。接続確認GETの初期値は`/health`、画像送信POSTの初期値は`/recognize`。例えば`/hello`と`/image`に変更でき、自動送信も保存された画像送信パスを使用する。以下のデータ形式はパスを変更しても同じ。

HTTP 200、`Content-Type: application/json`。

```json
{"status":"ok","schemaVersion":1}
```

既存の`{"status":"ok"}`も接続確認画面では受信できる。

## POST /recognize

### 簡易応答形式（追加対応）

正式v1形式は維持したまま、次の3値だけの応答も受け取れる。

```json
{"displayName":"山田","affiliation":"軽音サークル","bio":"ギター担当です"}
```

- profileで囲まず、トップレベルに3項目を返す。3つとも文字列とし、所属・一言がなければ空文字を返す。displayNameは空文字不可。
- requestId・trackIdなど正式形式の項目を1つでも含めた場合は正式v1として検証する。不完全な正式形式を簡易形式に読み替えない。
- 簡易形式はHTTPリクエストを送った顔へ反映する。リセット・モード変更・顔の見失いによる古い応答破棄は維持。
- 人物IDや類似度は捏造しない。カードは「PROFILE・受信済み」と表示し、正式形式の「照合一致」と区別する。
- 未登録を明示したい場合は、後述の正式v1不一致形式を返す。

`Content-Type: multipart/form-data`。一度に顔1つ、JPEG1枚を送る。リクエスト全体をapplication/jsonにはしない。

| 項目 | 型 | 内容 |
| --- | --- | --- |
| image | file | image/jpeg。ファイル名capture.jpg |
| schemaVersion | form文字列 | `1` |
| requestId | form文字列 | 送信ごとのUUID。応答へそのまま返す |
| trackId | form文字列 | Androidの一時的な追跡対象UUID。応答へそのまま返す |
| capturedAtMs | form文字列 | フレーム取得付近のUnix時刻（ミリ秒）。順序管理・診断用 |
| imageKind | form文字列 | 通常は`face_crop`。手動の全体画像テストは`full_frame` |

`trackId`は登録者のuserIdでもML Kitの数値IDでもない。人物識別には画像を使い、trackIdの値から誰なのかを決めない。

### JPEGの前処理

- ARCoreから取得した、顔検出と同じフレームを使う。
- 縦横回転を補正した正立画像。鏡像化はしない。
- 顔矩形の左右に各25%の顔幅、上下に各25%の顔高さを追加し、画像境界で切る。
- 最大辺512px、縦横比維持、小さい画像の拡大なし、JPEG品質85。
- 顔の傾き・ランドマーク位置合わせはPython側で行う。Android側では顔を変形しない。
- OpenCVで受信するなら、JPEGのbytesを`numpy.frombuffer`と`cv2.imdecode`などでBGR画像へ復元する。
- 余白内に他の顔が入った場合、切り抜き中央の対象顔を優先する。対象が曖昧なら不一致として返し、隣人を選ばない。
- `full_frame`は既存の手動疎通確認用（最大辺960px）。自動の人物別表示は`face_crop`だけ使用する。

Pythonの受信口例：

```python
@app.post("/recognize")
async def recognize(
    image: UploadFile = File(...),
    schemaVersion: int = Form(...),
    requestId: str = Form(...),
    trackId: str = Form(...),
    capturedAtMs: int = Form(...),
    imageKind: str = Form(...),
):
    # 画像を復元し、登録特徴量と照合する。
    # requestIdとtrackIdは変更せず応答へコピーする。
    ...
```

### 一致した場合

HTTP 200、`Content-Type: application/json`。以下のキーは必須。

```json
{
  "schemaVersion": 1,
  "requestId": "受信したrequestId",
  "trackId": "受信したtrackId",
  "matched": true,
  "userId": "user_001",
  "similarity": 0.82,
  "profile": {
    "displayName": "表示名",
    "affiliation": "所属や担当",
    "bio": "一言プロフィール"
  }
}
```

- displayNameとuserIdは空文字にしない。affiliationとbioは省略可能（推奨は空文字）。
- similarityは0〜1の数値。確率や百分率とは限らない。モデル固有のスコアはPython側で仕様に合わせる。
- 「一致」とみなす閾値はPython側で決め、Androidはmatchedを使う。
- プロフィールはPython側がDB担当の処理を呼び、まとめて返す。AndroidからDBへ直接接続しない。

### 未登録・一致しない場合

```json
{
  "schemaVersion": 1,
  "requestId": "受信したrequestId",
  "trackId": "受信したtrackId",
  "matched": false,
  "userId": null,
  "similarity": 0.25,
  "profile": null
}
```

有効なJPEGでも顔が認識できない場合はこの形式（similarityは0でもよい）。不一致はHTTPエラーにしない。

### エラー

- 400：壊れたJPEGなど処理できない入力。
- 422：必須項目の不足や形式違い。
- 503：モデルやDBが利用できない。
- 本文は例：`{"detail":"model not ready"}`。
- Pythonはエラー時に別人の仮プロフィールや前回の成功結果を返さない。

## Android側の送信管理

- 初期状態は停止。切り抜き確認・仮応答は外部送信なし。ユーザーがPC送信を選んだ場合だけ実通信する。
- 安定して検出できた顔を対象とし、端末全体で同時1リクエスト、開始間隔は最低1秒。
- 複数顔は未送信・最も長く待っている対象から順に処理。
- 一致済みは10秒、不一致・失敗は3秒を基本の再試行間隔とする。
- モード変更、空間リセット、追跡対象の再割り当てで、古い応答を無効化する。
- 「停止」は以降の画像取得・送信を停止し、表示結果をクリアする。すでに送ったリクエストはPCで処理される可能性があるが、応答を表示へ反映しない。
- requestId、trackId、schemaVersionを検証。応答時に対象が1200ms以上見失われている場合は表示へ反映しない。
- 保存は端末のメモリ内のみ。顔JPEGはファイルやGitへ保存しない。

## PCなしの検証

「顔JPEG・認識設定」→「切り抜き確認」で余白・向きを確認する。
「仮応答でプロフィール表示」で、同じ応答パーサーを通して人ごとの表示を確認する。
仮応答は実際の識別ではなく、`[仮] テスト人物 N`と表示される。

PCが準備できたら「PC接続」でベースURLを保存・接続確認し、「顔JPEG・認識設定」から「PCへ自動送信を開始」を選ぶ。
