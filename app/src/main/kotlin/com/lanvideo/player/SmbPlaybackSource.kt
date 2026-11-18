package com.lanvideo.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException

@UnstableApi
internal class SmbPlaybackSource(
    private val login: SmbLibrary.Login,
    private val path: String
) : DataSource.Factory, AutoCloseable {
    val trace = login.trace("播放共享视频（Media3 ExoPlayer）")
    val mediaUri: Uri = Uri.Builder().scheme("smb").authority("source")
        .appendPath("video." + path.substringAfterLast('.', "bin")).build()

    private var stream: SmbLibrary.Stream? = null
    private var closed = false

    override fun createDataSource(): DataSource = Reader()

    // ExoPlayer can reopen the data source at another byte position when seeking.
    // The file handle and read cache belong to the playback session.
    @Synchronized
    private fun openStream(): SmbLibrary.Stream {
        if (closed) throw IOException("播放会话已关闭")
        return stream ?: try {
            SmbLibrary.Stream(login, path, trace).also { stream = it }
        } catch (error: Exception) {
            throw playbackIoError("SMB 打开视频失败", error)
        }
    }

    private fun invalidateStream(failed: SmbLibrary.Stream) {
        synchronized(this) {
            if (stream === failed) stream = null
        }
        failed.close()
    }

    @Synchronized
    override fun close() {
        closed = true
        stream?.close()
        stream = null
    }

    private fun playbackIoError(message: String, error: Exception): IOException =
        IOException(message, error.takeIf { BuildConfig.DEBUG })

    private inner class Reader : BaseDataSource(true) {
        private var current: SmbLibrary.Stream? = null
        private var uri: Uri? = null
        private var position = 0L
        private var remaining = 0L

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val opened = openStream()
            if (dataSpec.position > opened.length) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }

            position = dataSpec.position
            val available = opened.length - position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
            current = opened
            uri = dataSpec.uri
            transferStarted(dataSpec)
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else dataSpec.length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val opened = current ?: throw IOException("SMB 数据源尚未打开")
            val count = try {
                opened.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
            } catch (error: IOException) {
                invalidateStream(opened)
                throw playbackIoError("SMB 读取视频失败", error)
            }
            if (count < 0) {
                remaining = 0
                return C.RESULT_END_OF_INPUT
            }
            position += count
            remaining -= count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            if (current != null) {
                current = null
                uri = null
                transferEnded()
            }
        }
    }
}
