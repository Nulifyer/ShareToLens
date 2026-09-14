package dev.nulifyer.sharetolens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nulifyer.sharetolens.ui.ShareToLensScreen
import dev.nulifyer.sharetolens.ui.WebSessionStore
import dev.nulifyer.sharetolens.ui.openExternalBrowser
import dev.nulifyer.sharetolens.ui.theme.ShareToLensTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: ShareToLensViewModel by viewModels()

    private var pendingCameraFile: File? = null

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.search(uri, SearchOrigin.Launcher)
    }

    private val camera = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { saved ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (saved && file != null && file.length() > 0L) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            viewModel.search(uri, SearchOrigin.Launcher, file)
        } else {
            file?.delete()
            if (saved) showMessage(R.string.msg_error_camera_image)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingCameraFile = savedInstanceState
            ?.getString(KEY_PENDING_CAMERA_FILE)
            ?.let(::File)

        val needsInitialization = viewModel.uiState.value == LensUiState.Initializing
        if (needsInitialization) ImagePreprocessor.cleanStaleFiles(cacheDir)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            ShareToLensTheme {
                ShareToLensScreen(
                    state = state,
                    onChoosePhoto = ::choosePhoto,
                    onTakePhoto = ::takePhoto,
                    onRetry = viewModel::retry,
                    onNewSearch = {
                        viewModel.beginNewSearch()
                        WebSessionStore.clear {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) viewModel.finishNewSearch()
                            }
                        }
                    },
                    onExit = {
                        if (viewModel.backAtRoot()) finish()
                    },
                    onOpenLink = { url ->
                        if (!openExternalBrowser(this, url)) {
                            showMessage(R.string.msg_error_no_browser)
                        }
                    },
                )
            }
        }

        if (needsInitialization) {
            WebSessionStore.clear {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) viewModel.initialize(sharedImage(intent))
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCameraFile?.let { outState.putString(KEY_PENDING_CAMERA_FILE, it.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            pendingCameraFile?.delete()
            WebSessionStore.clear()
        }
        super.onDestroy()
    }

    private fun choosePhoto() {
        try {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        } catch (_: ActivityNotFoundException) {
            showMessage(R.string.msg_error_no_picker)
        }
    }

    private fun takePhoto() {
        val cameraDir = File(cacheDir, "camera")
        if (!cameraDir.exists() && !cameraDir.mkdirs()) {
            showMessage(R.string.msg_error_generic)
            return
        }

        val file = try {
            File.createTempFile("lens-camera-", ".jpg", cameraDir)
        } catch (_: Exception) {
            showMessage(R.string.msg_error_generic)
            return
        }

        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        try {
            camera.launch(uri)
        } catch (_: ActivityNotFoundException) {
            pendingCameraFile = null
            file.delete()
            showMessage(R.string.msg_error_no_camera)
        }
    }

    private fun showMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("image/") != true) return null
        val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val uri = stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        return uri?.takeIf { it.scheme == "content" }
    }

    private companion object {
        const val KEY_PENDING_CAMERA_FILE = "pending_camera_file"
    }
}
