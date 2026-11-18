package com.lanvideo.player

import android.os.SystemClock
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes.FILE_ATTRIBUTE_DIRECTORY
import com.hierynomus.mssmb2.SMB2CreateDisposition.FILE_OPEN
import com.hierynomus.mssmb2.SMB2CreateOptions.FILE_NON_DIRECTORY_FILE
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.Share
import java.io.IOException
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object SmbLibrary {
    private const val READ_BLOCK_BYTES = 64 * 1024
    private const val MAX_READ_BYTES = 1024 * 1024
    private const val CACHE_BLOCKS = 128
    private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "3gp", "mpg", "mpeg")
    private val config = SmbConfig.builder()
        .withReadBufferSize(MAX_READ_BYTES)
        .withTimeout(12, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .withSigningRequired(true)
        .build()

    class Login(val host: String, val share: String, val account: String, val password: String) {
        fun trace(operation: String): SmbDiagnostics.Attempt {
            val format = when {
                '\\' in account -> "计算机或域\\用户名"
                '@' in account -> "邮箱或 UPN"
                else -> "用户名"
            }
            return SmbDiagnostics.begin(operation, host, share, format)
        }

        fun authentication(): AuthenticationContext {
            val user = account.substringAfter('\\')
            val domain = account.substringBefore('\\', "")
            return AuthenticationContext(user, password.toCharArray(), domain)
        }
    }

    data class Entry(val name: String, val directory: Boolean, val size: Long)

    fun list(login: Login, path: String): List<Entry> {
        val trace = login.trace(if (path.isEmpty()) "浏览共享根目录" else "浏览共享子目录")
        return SMBClient(config).use { client ->
            connect(client, login, trace).use { connection ->
                openShare(connection, login, trace).use { disk ->
                    val entries = trace.step("读取目录列表") { disk.list(path) }
                    trace.note("服务器返回 ${entries.size} 条目录记录")
                    entries.mapNotNull { info ->
                        val name = info.fileName
                        if (name == "." || name == "..") return@mapNotNull null
                        val directory = info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY.value != 0L
                        if (directory || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in videoExtensions) {
                            Entry(name, directory, info.endOfFile)
                        } else null
                    }.sortedWith(compareByDescending<Entry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                }
            }
        }
    }

    private fun connect(client: SMBClient, login: Login, trace: SmbDiagnostics.Attempt): Connection =
        trace.step("TCP 连接与 SMB 协商") { client.connect(login.host) }.also { connection ->
            trace.note("协商协议=${connection.negotiatedProtocol.dialect}")
        }

    private fun openShare(connection: Connection, login: Login, trace: SmbDiagnostics.Attempt): DiskShare {
        val session = trace.step("Windows 账号认证") { connection.authenticate(login.authentication()) }
        trace.note("会话签名=${session.isSigningRequired} · 来宾=${session.isGuest}")
        return trace.step("连接共享文件夹") {
            val share = session.connectShare(login.share)
            if (share is DiskShare) share else {
                share.close()
                throw IOException("该共享不是文件夹")
            }
        }
    }

    class Stream(login: Login, path: String, private val trace: SmbDiagnostics.Attempt) : AutoCloseable {
        private val client = SMBClient(config)
        private var connection: Connection? = null
        private var share: Share? = null
        private var file: File? = null
        val length: Long
        private var closed = false
        private var readFailureLogged = false
        private val cache: SmbReadCache
        private var playerReads = 0L
        private var playerBytes = 0L
        private var smbReads = 0L
        private var remoteBytes = 0L
        private var readNanos = 0L
        private var maxReadMillis = 0L
        private var largestRequest = 0

        init {
            try {
                val connected = connect(client, login, trace).also { connection = it }
                val disk = openShare(connected, login, trace).also { share = it }
                val opened = trace.step("打开视频文件") {
                    disk.openFile(
                        path, EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.allOf(SMB2ShareAccess::class.java),
                        FILE_OPEN, EnumSet.of(FILE_NON_DIRECTORY_FILE)
                    )
                }.also { file = it }
                length = trace.step("读取文件大小") { opened.fileInformation.standardInformation.endOfFile }
                val maxRead = connected.negotiatedProtocol.maxReadSize.coerceIn(1, MAX_READ_BYTES)
                val blockSize = minOf(maxRead, READ_BLOCK_BYTES)
                cache = SmbReadCache(length, blockSize, CACHE_BLOCKS, maxRead) { position, bytes, offset, size ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    smbReads++
                    largestRequest = maxOf(largestRequest, size)
                    try {
                        opened.read(bytes, position, offset, size).also { count ->
                            if (count > 0) remoteBytes += count.toLong()
                        }
                    } finally {
                        val elapsed = SystemClock.elapsedRealtimeNanos() - start
                        readNanos += elapsed
                        maxReadMillis = maxOf(maxReadMillis, elapsed / 1_000_000)
                    }
                }
                trace.note("播放缓存=$blockSize 字节/块 · 最多 $CACHE_BLOCKS 块 · SMB 顺序读取上限=$maxRead 字节")
            } catch (error: Exception) {
                close()
                throw error
            }
        }

        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (file == null) throw IOException("连接已关闭")
            if (position >= length) return -1
            playerReads++
            return try {
                cache.readAt(position, buffer, offset, size).also { count ->
                    if (count > 0) playerBytes += count.toLong()
                }
            } catch (error: RuntimeException) {
                if (!readFailureLogged) {
                    trace.failure("读取视频数据（字节偏移 $position）", error)
                    readFailureLogged = true
                }
                throw IOException("读取共享视频失败", error)
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            if (playerReads > 0) {
                val throughput = if (readNanos > 0L) remoteBytes * 1_000.0 / readNanos else 0.0
                val rate = String.format(Locale.ROOT, "%.2f", throughput)
                trace.note("播放读取统计 · 播放器请求=$playerReads · SMB 请求=$smbReads · " +
                    "播放器字节=$playerBytes · 网络字节=$remoteBytes · 最大单次请求=$largestRequest 字节 · " +
                    "SMB 读取平均=$rate MB/s · 最慢 SMB 读=$maxReadMillis ms")
            }
            for (resource in listOf(file, share, connection, client)) {
                try {
                    resource?.close()
                } catch (_: Exception) {
                    // Release the remaining SMB resources even if one close fails.
                }
            }
            file = null
            share = null
            connection = null
        }
    }
}
