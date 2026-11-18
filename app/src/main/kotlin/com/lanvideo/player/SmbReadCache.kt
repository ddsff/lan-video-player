package com.lanvideo.player

internal class SmbReadCache(
    private val length: Long,
    private val blockSize: Int,
    private val maxBlocks: Int,
    maxReadSize: Int,
    private val readBlock: (position: Long, buffer: ByteArray, offset: Int, size: Int) -> Int
) {
    private class Block(val bytes: ByteArray, val count: Int)

    // Keep distant audio, video and index positions cached at the same time.
    private val blocks = LinkedHashMap<Long, Block>(16, .75f, true)
    private val batchBlocks = (maxReadSize / blockSize).coerceIn(1, maxBlocks)
    private val batchBuffer by lazy { ByteArray(batchBlocks * blockSize) }
    private var nextSequentialMiss = -1L
    private var sequentialMisses = 0

    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position >= length) return -1

        val requested = minOf(size.toLong(), length - position).toInt()
        var copied = 0
        while (copied < requested) {
            val current = position + copied
            val within = (current % blockSize).toInt()
            val start = current - within
            val block = blocks[start] ?: load(start)
            if (within >= block.count) break
            val count = minOf(requested - copied, block.count - within)
            block.bytes.copyInto(buffer, offset + copied, within, within + count)
            copied += count
        }
        return if (copied == 0) -1 else copied
    }

    private fun load(start: Long): Block {
        // Increase request size only after cache misses advance contiguously.
        sequentialMisses = if (start == nextSequentialMiss) minOf(sequentialMisses + 1, 3) else 1
        val limit = when (sequentialMisses) {
            1 -> 1
            2 -> minOf(4, batchBlocks)
            else -> batchBlocks
        }
        val available = length - start
        var pages = 1
        while (pages < limit && pages.toLong() * blockSize < available &&
            !blocks.containsKey(start + pages.toLong() * blockSize)) {
            pages++
        }
        val requested = minOf(pages.toLong() * blockSize, available).toInt()
        val bytes = if (pages == 1) takeBlockBuffer() else batchBuffer
        var read = 0
        while (read < requested) {
            val chunk = readBlock(start + read, bytes, read, requested - read)
            if (chunk <= 0) break
            read += chunk
        }
        nextSequentialMiss = start + read
        if (pages == 1 || read == 0) return Block(bytes, read).also {
            if (read > 0) blocks[start] = it
        }

        var offset = 0
        while (offset < read) {
            val size = minOf(blockSize, read - offset)
            val page = takeBlockBuffer()
            bytes.copyInto(page, 0, offset, offset + size)
            blocks[start + offset] = Block(page, size)
            offset += size
        }
        return blocks.getValue(start)
    }

    private fun takeBlockBuffer(): ByteArray {
        if (blocks.size < maxBlocks) return ByteArray(blockSize)
        val oldest = blocks.entries.iterator()
        return oldest.next().value.bytes.also { oldest.remove() }
    }
}
