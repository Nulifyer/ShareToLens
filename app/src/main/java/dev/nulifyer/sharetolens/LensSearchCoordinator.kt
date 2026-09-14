package dev.nulifyer.sharetolens

import android.content.ContentResolver
import android.net.Uri
import java.io.File

/**
 * Owns one Lens search from local preprocessing through the upload response.
 * Callers only need to start or cancel a search; temporary files and the
 * blocking upload implementation remain private to this module.
 */
internal class LensSearchCoordinator(
    private val contentResolver: ContentResolver,
    private val cacheDir: File,
) {
    private val lock = Any()

    @Volatile
    private var activeOperation: Operation? = null

    fun search(
        uri: Uri,
        userAgent: String,
        listener: Listener,
    ): LensUploadClient.Result {
        val operation = Operation()
        synchronized(lock) {
            activeOperation?.cancel()
            activeOperation = operation
        }

        try {
            listener.onPreparing()
            ImagePreprocessor.prepare(
                contentResolver,
                uri,
                cacheDir,
                operation::isCancelled,
            ).use { preparedImage ->
                operation.throwIfCancelled()
                val uploader = LensUploadClient()
                operation.uploader = uploader
                return uploader.upload(
                    preparedImage,
                    userAgent,
                    object : LensUploadClient.Listener {
                        override fun onProgress(percent: Int) = listener.onProgress(percent)

                        override fun onRetry(nextAttempt: Int, maxAttempts: Int) {
                            listener.onRetry(nextAttempt, maxAttempts)
                        }
                    },
                )
            }
        } finally {
            synchronized(lock) {
                if (activeOperation === operation) activeOperation = null
            }
        }
    }

    fun cancel() {
        synchronized(lock) { activeOperation?.cancel() }
    }

    internal interface Listener {
        fun onPreparing()
        fun onProgress(percent: Int)
        fun onRetry(nextAttempt: Int, maxAttempts: Int)
    }

    private class Operation {
        @Volatile
        private var cancelled = false

        @Volatile
        var uploader: LensUploadClient? = null

        fun cancel() {
            cancelled = true
            uploader?.cancel()
        }

        fun isCancelled(): Boolean = cancelled || Thread.currentThread().isInterrupted

        fun throwIfCancelled() {
            if (isCancelled()) throw UploadCancelledException()
        }
    }
}
