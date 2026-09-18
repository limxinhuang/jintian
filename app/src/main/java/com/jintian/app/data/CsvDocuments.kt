package com.jintian.app.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

class CsvDocuments(private val resolver: ContentResolver) {
    fun read(uri: Uri): CsvSnapshot {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while(true) {
                val size = input.read(buffer)
                if(size < 0) break
                require(output.size() + size <= CsvBackup.MAX_BYTES) { "文件超过 10 MB，请选择应用导出的 CSV 备份" }
                output.write(buffer,0,size)
            }
            output.toByteArray()
        } ?: throw IOException("无法打开文件")
        return CsvBackup.decode(bytes)
    }
    fun write(uri: Uri, bytes: ByteArray) {
        val output = resolver.openOutputStream(uri,"wt") ?: throw IOException("无法创建文件")
        output.use { it.write(bytes); it.flush() }
    }
}
