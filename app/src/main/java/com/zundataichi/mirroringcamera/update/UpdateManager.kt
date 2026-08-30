package com.zundataichi.mirroringcamera.update

import android.content.Context
import android.util.Log
import com.zundataichi.mirroringcamera.BuildConfig
import com.zundataichi.mirroringcamera.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * アプリ本体の更新（確認・取得・インストール）をまとめて受け持つ。
 *
 * 設定画面と、どの画面にも出せる更新ダイアログの両方から使い、
 * 画面の作り直しをまたいで進行状況を保つ必要があるので、
 * Activity ではなく [com.zundataichi.mirroringcamera.MirroringCameraApp] が1つだけ持つ。
 */
class UpdateManager(
    context: Context,
    private val settingsStore: SettingsStore,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
) {

    /** 更新まわりの進行状況。 */
    sealed interface State {
        data object Idle : State

        data object Checking : State

        /** 最新だった（手動確認のときだけ「最新です」と伝えるために使う）。 */
        data object UpToDate : State

        data class Downloading(val progress: Float) : State

        /** インストーラーを起動済み。ユーザーが同意すればアプリは置き換わる。 */
        data object Installing : State

        data class Failed(val message: String) : State
    }

    private val releaseClient = GitHubReleaseClient(BuildConfig.GITHUB_REPOSITORY)
    private val apkInstaller = ApkInstaller(context.applicationContext)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /** 確認と取得が同時に走らないようにする。 */
    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 利用者に提示すべき新しいリリース。無ければ null。 */
    private val _availableRelease = MutableStateFlow<AppRelease?>(null)
    val availableRelease: StateFlow<AppRelease?> = _availableRelease.asStateFlow()

    val versionName: String get() = currentVersionName

    val repository: String get() = BuildConfig.GITHUB_REPOSITORY

    /**
     * 新しいリリースがあるか確認する。
     *
     * @param manual 設定画面から押された確認。自動確認の設定・確認間隔・
     *   スキップ指定をすべて無視して、結果をそのまま返す。
     */
    suspend fun checkForUpdate(manual: Boolean): Result<AppRelease?> = mutex.withLock {
        if (!manual) {
            if (!settingsStore.autoUpdateCheckEnabled.value) return Result.success(null)
            // 起動のたびに通信すると、電波の悪い現場では待たせるだけになる
            val elapsed = System.currentTimeMillis() - settingsStore.lastUpdateCheckAt
            if (elapsed in 0 until MIN_CHECK_INTERVAL_MS) return Result.success(null)
        }

        _state.value = State.Checking
        val result = releaseClient.fetchLatestRelease()
        settingsStore.setLastUpdateCheckAt(System.currentTimeMillis())

        return result.fold(
            onSuccess = { release ->
                val isNewer = release != null && release.isNewerThan(currentVersionName)
                // スキップ指定は「黙っていてほしい」であって「もう更新しない」ではないので、
                // 自分から確認しに来た手動確認では無視する
                val isSkipped = !manual && release?.tagName == settingsStore.skippedUpdateVersion
                val target = release?.takeIf { isNewer && !isSkipped }
                _availableRelease.value = target
                _state.value = if (target == null) State.UpToDate else State.Idle
                Result.success(target)
            },
            onFailure = { error ->
                Log.w(TAG, "更新の確認に失敗", error)
                _state.value = State.Failed(error.message ?: "更新の確認に失敗しました")
                Result.failure(error)
            },
        )
    }

    /** APK を取得してインストーラーを起動する。 */
    suspend fun downloadAndInstall(release: AppRelease): Result<Unit> = mutex.withLock {
        if (!apkInstaller.canInstallPackages()) {
            // 許可が無いままだと必ず失敗するので、設定画面へ送ってから理由を出す
            apkInstaller.requestInstallPermission()
            _state.value = State.Failed(INSTALL_PERMISSION_MESSAGE)
            return Result.failure(IllegalStateException("install permission not granted"))
        }

        _state.value = State.Downloading(0f)
        return apkInstaller
            .download(release) { progress -> _state.value = State.Downloading(progress) }
            .fold(
                onSuccess = { apk ->
                    if (apkInstaller.install(apk)) {
                        _state.value = State.Installing
                        Result.success(Unit)
                    } else {
                        _state.value = State.Failed("インストーラーを起動できませんでした")
                        Result.failure(IllegalStateException("failed to launch installer"))
                    }
                },
                onFailure = { error ->
                    _state.value = State.Failed(error.message ?: "APK のダウンロードに失敗しました")
                    Result.failure(error)
                },
            )
    }

    /** このバージョンについては、次のリリースが出るまで自動で知らせない。 */
    fun skipRelease(release: AppRelease) {
        settingsStore.setSkippedUpdateVersion(release.tagName)
        dismissRelease()
    }

    /** 今回は閉じるだけ。次の起動でまた知らせる。 */
    fun dismissRelease() {
        _availableRelease.value = null
        _state.value = State.Idle
    }

    fun resetState() {
        _state.value = State.Idle
    }

    /**
     * 画面から呼ぶ確認・取得は、必ずこのクラスの scope で走らせる。
     *
     * 画面（Composable）の scope で launch すると、回転などで作り直されたときに
     * 途中で打ち切られ、進行中を示したまま戻らなくなる。この画面は
     * `screenOrientation="fullSensor"` で回転のたびに作り直される。
     */
    private var activeJob: Job? = null

    private fun launchExclusive(block: suspend () -> Unit) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch { runCatching { block() } }
    }

    /** 設定画面の「アップデートを確認」。 */
    fun startManualCheck() = launchExclusive { checkForUpdate(manual = true) }

    /** ダイアログの「インストール」。 */
    fun startDownloadAndInstall(release: AppRelease) =
        launchExclusive { downloadAndInstall(release) }

    /** 起動時の自動確認。通信に失敗しても、アプリの起動を妨げない。 */
    fun checkOnStartup() = launchExclusive { checkForUpdate(manual = false) }

    private companion object {
        const val TAG = "UpdateManager"

        /** 自動確認の最短間隔（24時間）。 */
        const val MIN_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        const val INSTALL_PERMISSION_MESSAGE =
            "「不明なアプリのインストール」を許可してから、もう一度お試しください。"
    }
}
