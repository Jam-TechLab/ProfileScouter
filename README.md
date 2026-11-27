# Profile Scouter

顔を認識して、その人の横へプロフィールを表示するAndroidアプリ。
Kotlin / Jetpack Compose / Material 3 / ARCore / ML Kitを使用。

## 構成

小規模なMVVMを基本に、UIは機能ごと、通信・保存と共通モデルは役割ごとに配置する。

```text
com.example.profilescouter/
├── MainActivity.kt           起動・画面切り替え
├── ui/
│   ├── scouter/             AR画面・ScouterState・カード・追跡と座標変換
│   ├── conversation/        会話履歴画面・ConversationViewModel
│   ├── registration/        登録画面・RegistrationViewModel・写真の準備
│   ├── connection/          接続設定画面
│   ├── designsystem/        共通UI部品
│   └── theme/               色・書体
├── data/
│   ├── conversation/        会話Repository・HTTPクライアント・応答解析
│   ├── recognition/         顔認識Repository・認識応答の検証
│   └── registration/        登録Repository
├── model/                   人物・顔・会話・登録のデータと判定規則
└── core/
    ├── camera/              画像取得・変換・JPEG化
    └── network/             接続先URL・APIパスの共通検証
```

画面の操作をViewModelへ渡し、ViewModelがRepositoryを呼んで表示状態を更新する。
登録と会話のように画面回転・切り替えをまたいで保持する状態はViewModelが担当する。
ARの認識状態はカメラ画面と一緒に終了するため、`ui/scouter/ScouterState.kt` という画面専用の状態ホルダーを使う。
カメラ・アンカー・描画はAR画面の寿命に合わせて管理する。

`ConversationRepository` は通信と端末保存の窓口。送信後の最新会話取得もここで行い、ViewModelはHTTPクライアントを直接生成しない。
認識JSONの解析は `data/recognition/FaceResponse.kt`、データ型は `model/FaceRecognition.kt` に分ける。
テストのパッケージも対象コードと同じ配置にする。

この規模では空のRepositoryインターフェース・UseCase層・DIライブラリを追加しない。
既存クラスに役割を持たせ、未使用の旧実装は残さない。履歴はGitを参照する。

## 開発・連携

- 通信仕様：`API_CONTRACT.md`
- UIの共通ルール：`DESIGN_SYSTEM.md`
- ビルド：`gradlew.bat :app:assembleDebug`
- 単体テスト：`gradlew.bat :app:testDebugUnitTest`
- スマホとPCを同じWi-Fiへ接続し、アプリにPCのLAN内IPを設定する。
- 起動時からPC認識を有効にする。接続先が未設定の場合は設定へ案内する。AR画面で認識を停止・再開できる。
- 認識用と登録用の接続先は別々に設定する。登録APIは3方向JPEGを受け付け、JSONで結果を返す。
- Depthは使用せず、顔幅から距離を概算する。
- 下部の「カメラ／会話履歴」で切り替える。履歴を開くとカメラと録音は終了し、音声の送信・応答待ちは続ける。
- 顔枠・追跡ID・距離・認識の詳細は、設定の「詳細設定・動作確認」で表示する。通常は名前・所属・要約を中心に表示する。

実機のカメラ・AR・PCとの結合は、ビルドや単体テストとは別に確認する。

## 会話録音・共有履歴

人物認識をきっかけに録音し、相手の会話履歴をPCへ追加する。録音者の本人識別は不要。
設定・操作・実装の入口・実機確認の範囲は [CONVERSATIONS.md](CONVERSATIONS.md) を参照。
