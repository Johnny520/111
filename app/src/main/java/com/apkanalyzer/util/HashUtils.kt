package com.apkanalyzer.util

import java.io.File
import java.security.MessageDigest

object HashUtils {

    fun calculateMD5(file: File): String {
        return calculateHash(file, "MD5")
    }

    fun calculateSHA1(file: File): String {
        return calculateHash(file, "SHA-1")
    }

    fun calculateSHA256(file: File): String {
        return calculateHash(file, "SHA-256")
    }

    private fun calculateHash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
