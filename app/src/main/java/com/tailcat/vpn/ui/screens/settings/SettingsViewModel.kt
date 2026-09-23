package com.tailcat.vpn.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.update.ApkDownloader
import com.tailcat.vpn.core.update.GitHubReleaseClient
import com.tailcat.vpn.core.update.LatestRelease
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val current: String) : UpdateState
    data class Available(val release: LatestRelease) : UpdateState
    data class Downloading(val release: LatestRelease, val fraction: Float) : UpdateState
    data class Ready(val release: LatestRelease, val apkPath: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

class SettingsViewModel : ViewModel() {

    private val app = TailcatApplication.instance

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    private val downloader = ApkDownloader(app.cacheDir)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            GitHubReleaseClient.fetchLatest()
                .onSuccess { release ->
                    _updateState.value = if (release.isNewerThan(currentVersion())) {
                        UpdateState.Available(release)
                    } else {
                        UpdateState.UpToDate(currentVersion())
                    }
                }
                .onFailure { e ->
                    _updateState.value = UpdateState.Error(e.message ?: "Update check failed")
                }
        }
    }

    fun downloadUpdate(context: Context) {
        val available = _updateState.value as? UpdateState.Available ?: return
        if (downloadJob?.isActive == true) return
        val abi = com.tailcat.vpn.core.update.ApkInstaller.preferredAbi()
        val asset = available.release.apkAssetForAbi(abi)
        if (asset == null) {
            _updateState.value = UpdateState.Error("No APK for this device ABI ($abi)")
            return
        }
        downloadJob = viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(available.release, 0f)
            // Collect progress while downloading.
            val collectJob = launch {
                downloader.progress.collect { p ->
                    if (p.active) {
                        _updateState.value = UpdateState.Downloading(available.release, p.fraction)
                    }
                }
            }
            downloader.download(asset, expectedSha256 = null)
                .onSuccess { file ->
                    collectJob.cancel()
                    val mismatch = com.tailcat.vpn.core.update.ApkInstaller
                        .signatureMismatchReason(context, file)
                    if (mismatch != null) {
                        file.delete()
                        _updateState.value = UpdateState.Error(mismatch)
                    } else {
                        _updateState.value = UpdateState.Ready(available.release, file.absolutePath)
                        _uiEvent.emit("Update downloaded")
                    }
                }
                .onFailure { e ->
                    collectJob.cancel()
                    _updateState.value = UpdateState.Error(e.message ?: "Download failed")
                }
        }
    }

    fun openInstall(context: Context) {
        val ready = _updateState.value as? UpdateState.Ready ?: return
        val file = File(ready.apkPath)
        if (!file.exists()) {
            _updateState.value = UpdateState.Error("Downloaded file is missing")
            return
        }
        if (!com.tailcat.vpn.core.update.ApkInstaller.canRequestInstall(context)) {
            runCatching {
                context.startActivity(
                    com.tailcat.vpn.core.update.ApkInstaller.unknownSourcesIntent(context)
                )
            }
            viewModelScope.launch {
                _uiEvent.emit("Allow installs from this source, then tap Install again")
            }
            return
        }
        val intent = com.tailcat.vpn.core.update.ApkInstaller.installIntent(context, file)
        runCatching { context.startActivity(intent) }
            .onFailure { e ->
                _updateState.value = UpdateState.Error(e.message ?: "Could not start installer")
            }
    }

    fun openReleasePage(context: Context) {
        val url = when (val s = _updateState.value) {
            is UpdateState.Available -> s.release.htmlUrl
            is UpdateState.Ready -> s.release.htmlUrl
            else -> "https://github.com/OmarAlaaeldein/OpenTailcat/releases/latest"
        }
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
            )
        }
    }

    fun resetUpdate() {
        checkJob?.cancel()
        downloadJob?.cancel()
        downloader.reset()
        _updateState.value = UpdateState.Idle
    }

    private fun currentVersion(): String = com.tailcat.vpn.BuildConfig.VERSION_NAME
}
