package com.lanvideo.player

import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

internal object SmbDiagnostics {
    const val TAG = "LanVideoSMB"
    private const val MAX_LINES = 160
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val attempts = AtomicInteger()

    fun begin(operation: String, host: String, share: String, accountFormat: String): Attempt =
        Attempt(attempts.incrementAndGet()).also {
            val detail = "$operation · 地址=${safe(host)}:445 · 共享=${safe(share)} · 账号格式=$accountFormat · 要求签名"
            val systemDetail = if (BuildConfig.DEBUG) detail else
                "$operation · 地址=[已隐藏]:445 · 共享=[已隐藏] · 账号格式=$accountFormat · 要求签名"
            append(it.id, detail, error = false, systemDetail = systemDetail)
        }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    fun isLogonFailure(error: Throwable): Boolean =
        causes(error).any { it is SMBApiException && it.status == NtStatus.STATUS_LOGON_FAILURE }

    private fun causes(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { it.cause }.take(8)

    @Synchronized
    private fun append(id: Int, detail: String, error: Boolean, systemDetail: String = detail) {
        val prefix = "${LocalTime.now().format(clock)} [#$id] "
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(prefix + detail)
        if (error) Log.w(TAG, prefix + systemDetail) else Log.i(TAG, prefix + systemDetail)
    }

    private fun safe(value: String): String =
        value.take(180).map { if (it.isISOControl()) ' ' else it }.joinToString("")

    class Attempt(val id: Int) {
        fun note(detail: String) = append(id, detail, false)

        fun failure(stage: String, error: Exception, elapsed: Long? = null) {
            val causeChain = causes(error).toList()
            val smb = causeChain.filterIsInstance<SMBApiException>().firstOrNull()
            val types = causeChain.joinToString(" → ") { it.javaClass.simpleName }
            val elapsedText = elapsed?.let { "（$it ms）" }.orEmpty()
            val status = smb?.let {
                val code = String.format(Locale.ROOT, "0x%08X", it.statusCode)
                " · ${it.failedCommand} · ${it.status} ($code)"
            }.orEmpty()
            val errno = causeChain.filterIsInstance<ErrnoException>().firstOrNull()?.let {
                " · errno=${OsConstants.errnoName(it.errno) ?: it.errno}"
            }.orEmpty()
            val hint = if (causeChain.any { it is SMB2GuestSigningRequiredException }) {
                " · 服务器返回了来宾会话，无法满足 SMB 签名要求"
            } else ""
            // Exception messages may contain account names or server-controlled data.
            append(id, "$stage：失败$elapsedText · $types$status$errno$hint", true)
        }

        fun <T> step(stage: String, action: () -> T): T {
            val start = SystemClock.elapsedRealtime()
            note("$stage：开始")
            return try {
                action().also { note("$stage：成功（${SystemClock.elapsedRealtime() - start} ms）") }
            } catch (error: Exception) {
                failure(stage, error, SystemClock.elapsedRealtime() - start)
                throw error
            }
        }
    }
}
