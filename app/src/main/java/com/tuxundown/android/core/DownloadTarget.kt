package com.tuxundown.android.core

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * A seekable write destination for the download engine.
 *
 * v1.2: downloads are written DIRECTLY into the public Download
 * folder (or the user's SAF folder) as "<name>.part" — never staged
 * in the app's private directory. Both implementations support
 * positional writes, which the multi-threaded engine needs:
 * - [PfdTarget]: a ParcelFileDescriptor from MediaStore or SAF,
 *   written with Os.pwrite (positional, thread-safe).
 * - [FileTarget]: a plain file (legacy partials from older versions),
 *   via a synchronized RandomAccessFile.
 */
interface DownloadTarget {
    fun truncate(size: Long)
    fun writeAt(position: Long, buf: ByteArray, offset: Int, length: Int)
    /** Sequential stream starting at byte 0 (truncates first). */
    fun openStream(): OutputStream
    fun close()
}

class PfdTarget(private val pfd: ParcelFileDescriptor) : DownloadTarget {
    private val fd = pfd.fileDescriptor

    override fun truncate(size: Long) {
        Os.ftruncate(fd, size)
    }

    override fun writeAt(position: Long, buf: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val n = Os.pwrite(fd, buf, offset + written, length - written, position + written)
            if (n <= 0) throw IOException("写入下载文件失败")
            written += n
        }
    }

    override fun openStream(): OutputStream {
        truncate(0)
        return object : OutputStream() {
            private var pos = 0L
            override fun write(b: ByteArray, off: Int, len: Int) {
                writeAt(pos, b, off, len)
                pos += len
            }
            override fun write(b: Int) {
                writeAt(pos, byteArrayOf(b.toByte()), 0, 1)
                pos++
            }
        }
    }

    override fun close() {
        runCatching { pfd.close() }
    }
}

class FileTarget(file: File) : DownloadTarget {
    private val raf = RandomAccessFile(file, "rw")
    private val lock = Object()

    override fun truncate(size: Long) {
        synchronized(lock) { raf.setLength(size) }
    }

    override fun writeAt(position: Long, buf: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            raf.seek(position)
            raf.write(buf, offset, length)
        }
    }

    override fun openStream(): OutputStream {
        truncate(0)
        return object : OutputStream() {
            private var pos = 0L
            override fun write(b: ByteArray, off: Int, len: Int) {
                writeAt(pos, b, off, len)
                pos += len
            }
            override fun write(b: Int) {
                writeAt(pos, byteArrayOf(b.toByte()), 0, 1)
                pos++
            }
        }
    }

    override fun close() {
        runCatching { raf.close() }
    }
}
