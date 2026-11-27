# Profile Scouter

顔を認識して、その人の横へプロフィールを表示するAndroidアプリ。
Kotlin / Jetpack Compose / Material 3 / ARCore / ML Kitを使用。

## コードの入口

| 機能 | ファイル |
| --- | --- |
| 起動・画面切替・カメラ権限 | `MainActivity.kt` |
| 空間AR・顔検出・追従 | `SpatialScouter.kt` |
| 追跡ID・重複検出の判定 | `FaceTrackPolicy.kt` |
| カメラ／画面／空間の座標変換 | `SpatialMath.kt` |
| カメラ画像取得・YUV変換・JPEG化 | `CameraSnapshot.kt` |
| 認識への送信制御・応答解析 | `FaceRecognition.kt` |
| 認識HTTP・URL設定の検証 | `RecognitionClient.kt` |
| ARプロフィールの内容・描画 | `ProfileCard.kt` |
| 接続・設定のUI | `ConnectionPanel.kt` / `ScouterSettings.kt` |
| プロフィール入力・3方向撮影・確認UI | `OwnProfileScreen.kt` |
| 写真の読み込み・向き補正・正面の顔検証 | `RegistrationPhoto.kt` |
| 登録送信状態の維持 | `RegistrationViewModel.kt` |
| 登録HTTP・JSON結果の検証 | `RegistrationClient.kt` |
| 共通UI・色・文字 | `ui/designsystem/` / `ui/theme/` |

画面、HTTP処理、画像処理、追跡の判定は役割ごとに分離する。
1つの小機能だけのファイルを追加する前に、上記の既存ファイルに含められるか確認する。
未使用になった旧実装は残さず、履歴が必要ならGitを参照する。

## 開発・連携

- 通信仕様：`API_CONTRACT.md`
- UIの共通ルール：`DESIGN_SYSTEM.md`
- ビルド：`gradlew.bat :app:assembleDebug`
- 単体テスト：`gradlew.bat :app:testDebugUnitTest`
- スマホとPCを同じWi-Fiへ接続し、アプリにPCのLAN内IPを設定する。
- 起動時からPC認識を有効にする。接続先が未設定の場合は設定へ案内する。AR画面で認識を停止・再開できる。
- 認識用と登録用の接続先は別々に設定する。登録APIは3方向JPEGを受け付け、JSONで結果を返す。
- Depthは使用せず、顔幅から距離を概算する。

実機のカメラ・AR・PCとの結合は、ビルドや単体テストとは別に確認する。

## 会話録音・共有履歴

人物認識をきっかけに録音し、相手の会話履歴をPCへ追加する。録音者の本人識別は不要。
設定・操作・実装の入口・実機確認の範囲は [CONVERSATIONS.md](CONVERSATIONS.md) を参照。
