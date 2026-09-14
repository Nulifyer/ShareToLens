package dev.nulifyer.sharetolens

import android.app.Application
import android.net.Uri
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SearchOrigin {
    Launcher,
    Share,
}

internal enum class LensFailure {
    Generic,
    ImageTooLarge,
}

internal sealed interface LensUiState {
    data object Initializing : LensUiState
    data object Ready : LensUiState

    data class Preparing(val origin: SearchOrigin) : LensUiState

    data class Uploading(
        val origin: SearchOrigin,
        val percent: Int,
    ) : LensUiState

    data class Retrying(
        val origin: SearchOrigin,
        val attempt: Int,
        val maxAttempts: Int,
    ) : LensUiState

    data class Results(
        val origin: SearchOrigin,
        val url: String,
        val cookies: List<String>,
    ) : LensUiState

    data class Error(
        val origin: SearchOrigin,
        val failure: LensFailure,
    ) : LensUiState
}

internal fun LensUiState.originOrNull(): SearchOrigin? = when (this) {
    LensUiState.Initializing, LensUiState.Ready -> null
    is LensUiState.Preparing -> origin
    is LensUiState.Uploading -> origin
    is LensUiState.Retrying -> origin
    is LensUiState.Results -> origin
    is LensUiState.Error -> origin
}

internal fun LensUiState.shouldCloseAtRoot(): Boolean =
    this == LensUiState.Initializing ||
        this == LensUiState.Ready ||
        originOrNull() == SearchOrigin.Share

internal class ShareToLensViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = LensSearchCoordinator(application.contentResolver, application.cacheDir)
    private val userAgent by lazy { WebSettings.getDefaultUserAgent(application) }
    private val _uiState = MutableStateFlow<LensUiState>(LensUiState.Initializing)

    val uiState: StateFlow<LensUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var searchJob: Job? = null
    private var source: Source? = null
    private var generation = 0L

    fun initialize(sharedImage: Uri?) {
        if (initialized) return
        initialized = true
        if (sharedImage == null) {
            _uiState.value = LensUiState.Ready
        } else {
            search(sharedImage, SearchOrigin.Share)
        }
    }

    fun search(uri: Uri, origin: SearchOrigin, temporaryFile: File? = null) {
        cancelWork()
        cleanupSource()
        source = Source(uri, origin, temporaryFile)
        launchSearch()
    }

    fun retry() {
        if (source != null) launchSearch()
    }

    fun beginNewSearch() {
        cancelWork()
        cleanupSource()
        _uiState.value = LensUiState.Initializing
    }

    fun finishNewSearch() {
        if (_uiState.value != LensUiState.Initializing) return
        _uiState.value = LensUiState.Ready
    }

    /** Returns true when the activity should close instead of showing Ready. */
    fun backAtRoot(): Boolean {
        val current = _uiState.value
        val shouldClose = current.shouldCloseAtRoot()
        cancelWork()
        cleanupSource()
        if (!shouldClose) _uiState.value = LensUiState.Ready
        return shouldClose
    }

    private fun launchSearch() {
        val activeSource = source ?: return
        cancelWork()
        val activeGeneration = ++generation
        _uiState.value = LensUiState.Preparing(activeSource.origin)
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = coordinator.search(
                    activeSource.uri,
                    userAgent,
                    object : LensSearchCoordinator.Listener {
                        override fun onPreparing() {
                            updateIfCurrent(activeGeneration) {
                                LensUiState.Preparing(activeSource.origin)
                            }
                        }

                        override fun onProgress(percent: Int) {
                            updateIfCurrent(activeGeneration) {
                                LensUiState.Uploading(activeSource.origin, percent.coerceIn(0, 100))
                            }
                        }

                        override fun onRetry(nextAttempt: Int, maxAttempts: Int) {
                            updateIfCurrent(activeGeneration) {
                                LensUiState.Retrying(activeSource.origin, nextAttempt, maxAttempts)
                            }
                        }
                    },
                )
                if (generation == activeGeneration) {
                    activeSource.temporaryFile?.delete()
                    source = activeSource.copy(temporaryFile = null)
                    _uiState.value = LensUiState.Results(
                        activeSource.origin,
                        result.url,
                        result.cookies.toList(),
                    )
                }
            } catch (_: UploadCancelledException) {
                // A newer action owns the next state.
            } catch (_: CancellationException) {
                // A newer action owns the next state.
            } catch (_: ImagePreprocessor.InputTooLargeException) {
                updateIfCurrent(activeGeneration) {
                    LensUiState.Error(activeSource.origin, LensFailure.ImageTooLarge)
                }
            } catch (_: Exception) {
                updateIfCurrent(activeGeneration) {
                    LensUiState.Error(activeSource.origin, LensFailure.Generic)
                }
            }
        }
    }

    private fun updateIfCurrent(expectedGeneration: Long, state: () -> LensUiState) {
        if (generation == expectedGeneration) _uiState.value = state()
    }

    private fun cancelWork() {
        generation++
        coordinator.cancel()
        searchJob?.cancel()
        searchJob = null
    }

    private fun cleanupSource() {
        source?.temporaryFile?.delete()
        source = null
    }

    override fun onCleared() {
        cancelWork()
        cleanupSource()
    }

    private data class Source(
        val uri: Uri,
        val origin: SearchOrigin,
        val temporaryFile: File?,
    )
}
