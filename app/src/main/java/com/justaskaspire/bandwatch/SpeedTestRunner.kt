package com.justaskaspire.bandwatch

import java.net.HttpURLConnection
import java.net.URL

data class SpeedTestResult(val downloadMbps: Double, val uploadMbps: Double)

object SpeedTestRunner {
    enum class Stage { DOWNLOAD, UPLOAD }

    private const val DOWNLOAD_BYTES = 5_000_000
    private const val UPLOAD_BYTES = 2_000_000
    private const val BASE_URL = "https://speed.cloudflare.com"

    fun run(onStage: (Stage) -> Unit): SpeedTestResult {
        onStage(Stage.DOWNLOAD)
        val download = measureDownload()
        onStage(Stage.UPLOAD)
        val upload = measureUpload()
        return SpeedTestResult(download, upload)
    }

    private fun measureDownload(): Double {
        val url = URL("$BASE_URL/__down?bytes=$DOWNLOAD_BYTES&cacheBust=${System.nanoTime()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 90_000
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val start = System.nanoTime()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Download server returned HTTP $code")
            var received = 0L
            connection.inputStream.buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                }
            }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            if (received <= 0L || seconds <= 0.0) throw IllegalStateException("No download data received")
            return received * 8.0 / seconds / 1_000_000.0
        } finally {
            connection.disconnect()
        }
    }

    private fun measureUpload(): Double {
        val url = URL("$BASE_URL/__up?cacheBust=${System.nanoTime()}")
        val payload = ByteArray(UPLOAD_BYTES)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            useCaches = false
            setFixedLengthStreamingMode(UPLOAD_BYTES)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val start = System.nanoTime()
            connection.outputStream.use { output ->
                var offset = 0
                while (offset < payload.size) {
                    val count = minOf(64 * 1024, payload.size - offset)
                    output.write(payload, offset, count)
                    offset += count
                }
                output.flush()
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Upload server returned HTTP $code")
            connection.inputStream.use { input -> while (input.read() != -1) { /* drain response */ } }
            val seconds = (System.nanoTime() - start) / 1_000_000_000.0
            if (seconds <= 0.0) throw IllegalStateException("Upload time could not be measured")
            return UPLOAD_BYTES * 8.0 / seconds / 1_000_000.0
        } finally {
            connection.disconnect()
        }
    }
}
