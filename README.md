# WORK SAFE — 개발용 Android 베이스 앱

`LayoutImages/`의 SVG 시안을 Jetpack Compose로 구현한 로컬 데모 앱입니다.
로그인, 홈, 개인 정보 변경, 화면 모드 변경, 비밀번호 변경, 회원 탈퇴와
설정 메뉴·확인/성공/실패 다이얼로그를 라이트·다크 테마로 제공합니다.

홈의 낙상 경고와 센서값은 예시이며, 서버 상태는 `DEMO · 미연결`입니다.
BLE·Galaxy Watch·가속도 센서·서버 전송은 UI에서 실행하지 않습니다.

## 빌드와 실행

- JDK 17 또는 21, Android SDK Platform 35가 필요합니다.
- Gradle 8.9 Wrapper가 포함되어 있습니다. 최초 실행에는 의존성 다운로드가 필요합니다.
- Android Studio에서 저장소 루트를 열고 Gradle JDK를 17/21로 선택한 뒤 `app`을 실행합니다.
  JBR/JDK 25는 이 Gradle 버전과 맞지 않으므로 사용하지 않습니다.

macOS 설정:

```sh
export JAVA_HOME="$HOME/Library/Caches/worksafe-dev/jdk-21.0.13+9/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew --no-daemon :app:assembleDebug :app:lintDebug
```

다른 환경에서는 `JAVA_HOME`과 SDK 경로를 설치 위치에 맞게 지정합니다.
생성되는 APK: `app/build/outputs/apk/debug/app-debug.apk`

에뮬레이터 또는 Android 8.0 이상 기기를 연결한 뒤:

```sh
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -W -n com.example.safetymonitor/.MainActivity
```

여러 기기가 연결되어 있으면 adb 명령에 `-s <기기 시리얼>`을 추가합니다.

## 데모 계정과 상태 수명

| 항목 | 동작 |
|---|---|
| 초기 로그인 | 작업자 ID `1024`, 비밀번호 `0000` |
| 초기 프로필 | 홍길동 / 남 / 29 / 사원 / 생산1팀 |
| 개인 정보 | 이름·성별 공백 및 1~120 범위 밖 나이를 거절하며, 저장 후 홈과 헤더에 반영 |
| 비밀번호 | 현재 비밀번호, 새 비밀번호 4자 이상, 확인 일치를 검증 |
| 로그아웃 | 로그인 화면으로 이동. 계정·프로필·변경 비밀번호는 유지 |
| 회원 탈퇴 | 확인 후 현재 프로세스의 데모 계정을 비활성화. 재로그인 거절 |
| 테마 | SharedPreferences에 저장. 프로세스 재시작 후에도 유지 |

계정·프로필·비밀번호는 **프로세스 메모리에서만** 유지합니다.
화면 회전이나 Activity 재생성만으로 초기화되지 않으며, 비밀번호를 디스크에 저장하지 않습니다.
실제 서버 계정은 생성·수정·삭제하지 않습니다.

프로세스 종료 후에는 초기 데모 계정으로 복원됩니다.
최근 앱 화면에서 닫는 것만으로 프로세스 종료가 보장되지는 않으므로,
개발 중 확실하게 초기화하려면 다음을 사용합니다. 테마는 초기화하지 않습니다.

```sh
"$ANDROID_HOME/platform-tools/adb" shell am force-stop com.example.safetymonitor
"$ANDROID_HOME/platform-tools/adb" shell am start -W -n com.example.safetymonitor/.MainActivity
```

## 코드 위치

`app/src/main/java/com/example/safetymonitor/` 기준:

- `MainActivity.kt`: Compose 진입점과 시스템 바 테마.
- `ui/MainScreen.kt`: 화면 이동, 뒤로가기, 메뉴와 결과 다이얼로그 연결.
- `ui/WorkSafeViewModel.kt`: 로컬 계정 동작, 프로세스 수명 상태, 입력 검증, 테마 저장.
- `ui/WorkSafeState.kt`: 화면·프로필·다이얼로그 모델.
- `ui/WorkSafeTheme.kt`, `ui/WorkSafeComponents.kt`: 공통 팔레트·타이포그래피·카드·입력·버튼·헤더.
- `ui/LoginScreen.kt`, `ui/HomeScreen.kt`: 로그인과 홈.
- `ui/SettingsScreens.kt`, `ui/WorkSafeDialogs.kt`: 설정 화면, 메뉴와 다이얼로그.

기존 감지기·`SafetyManager`·HTTP 전송 코드는 보존하지만 데모 진입점에서는 호출하지 않습니다.
사용되지 않으며 동일 enum을 중복 선언하던 `DetectionResult.kt`만 제거했습니다.
시안 파일은 변경하지 않았습니다.

ADB/UIAutomator 조작 및 실제 스크린샷으로 다음을 확인했습니다.

- 잘못된 로그인 거절, 초기 계정 로그인, 로그아웃 후 홈 재진입 차단.
- 개인 정보 저장·홈 반영, 잘못된 나이 거절.
- 현재 비밀번호 오류·새 비밀번호 확인 불일치 거절, 변경 후 이전 비밀번호 거절·새 비밀번호 로그인.
- Activity 재생성 뒤에도 같은 프로세스의 프로필·변경 비밀번호 유지.
- 탈퇴 취소 시 계정 유지, 확인 시 재로그인 차단, Activity 재생성 뒤에도 비활성 상태 유지.
- 프로세스 재시작 시 초기 계정·프로필 복원 및 라이트/다크 설정 유지.
- 양 테마의 전체 화면·메뉴·다이얼로그, 회전 후 세션 유지.
- 짧은 화면에서 키보드가 열려도 헤더가 유지되고, 폼을 스크롤해 저장 버튼 실행 가능.

실시간 센서·Watch 데이터 및 서버 API 연동은 검증 범위가 아닙니다.

## 실시간 센서 화면 및 Android Studio 실행

- 프로젝트 루트(`settings.gradle.kts`가 있는 폴더)를 Android Studio에서 엽니다.
- Gradle JDK는 17 또는 21을 선택합니다. 현재 Gradle 8.9/Kotlin 2.0.20 구성에서 JDK 25는 사용하지 않습니다.
- Gradle Sync 후 `app` 실행 구성을 선택해 실행합니다. 데모 로그인: ID `1024`, 비밀번호 `0000`.
- 로그인 후 기존 홈 화면의 가속도 센서 행에 가속도 합성값(m/s²)이 약 100ms 간격으로 표시됩니다. 자이로 센서는 내부 낙상 판정에 사용하며 별도 표시 영역은 추가하지 않습니다. 가속도는 중력을 포함하므로 정지 시 합성값은 약 9.81m/s²입니다.
- 낙상 판정은 가속도계의 자유낙하/충격, 자이로의 큰 회전, 두 센서의 3초 정지를 확인합니다. 한 센서가 없거나 등록에 실패하면 낙상 감지를 비활성화하고, 사용 가능한 센서 값은 계속 표시합니다.
- 두 센서 모두 새 값을 수신해야 판정하며, 한 센서 값이 500ms 이상 지연되면 진행 중인 판정을 초기화합니다.
- 로그인하면 알림을 표시하는 포그라운드 서비스에서 측정을 시작합니다. 다른 화면/앱으로 이동하거나 화면을 잠그거나 최근 앱에서 화면을 제거해도 측정을 계속합니다. 로그아웃 또는 회원 탈퇴 시 서비스·센서·알림을 종료합니다. 앱을 다시 열면 측정 세션과 최신 값이 연결됩니다. Android 13 이상에서는 측정 알림 권한을 요청하며 거부하더라도 측정은 계속됩니다. 기기 재부팅이나 시스템 설정에서 앱 강제 종료 후에는 앱을 다시 열어야 합니다. 현재 변경은 화면의 실제 감지 표시이며 서버 전송은 연결하지 않습니다.

### 백그라운드 측정 검증

에뮬레이터에서 로그인, 홈 이동, 화면 잠금, Activity 작업 제거, 재진입, 로그아웃과 알림 정리를 확인하는 검증은 app/src/androidTest의 BackgroundMonitoringTestRunner에 있습니다. 앱과 androidTest APK를 설치한 뒤 다음 명령으로 실행합니다.

adb shell am instrument -w com.example.safetymonitor.test/com.example.safetymonitor.BackgroundMonitoringTestRunner
