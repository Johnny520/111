package com.apkanalyzer.model

data class ApkInfo(
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: Long = 0,
    val minSdkVersion: Int = 0,
    val targetSdkVersion: Int = 0,
    val appLabel: String = "",
    val permissions: List<String> = emptyList(),
    val activities: List<ComponentInfo> = emptyList(),
    val services: List<ComponentInfo> = emptyList(),
    val receivers: List<ComponentInfo> = emptyList(),
    val providers: List<ComponentInfo> = emptyList(),
    val certificateInfo: CertificateInfo? = null,
    val md5: String = "",
    val sha1: String = "",
    val sha256: String = "",
    val manifestXml: String = "",
    val iconBytes: ByteArray? = null,
    val nativeLibraries: List<String> = emptyList(),
    val resources: List<ResourceInfo> = emptyList()
)

data class ComponentInfo(
    val name: String,
    val exported: Boolean = false,
    val permission: String? = null,
    val intentFilters: List<String> = emptyList()
)

data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val validFrom: String,
    val validUntil: String,
    val signatureAlgorithm: String
)

data class ResourceInfo(
    val name: String,
    val type: String,
    val size: Long
)
