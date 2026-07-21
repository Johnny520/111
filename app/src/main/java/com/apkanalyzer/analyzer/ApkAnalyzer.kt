package com.apkanalyzer.analyzer

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.apkanalyzer.model.ApkInfo
import com.apkanalyzer.model.CertificateInfo
import com.apkanalyzer.model.ComponentInfo
import com.apkanalyzer.model.ResourceInfo
import com.apkanalyzer.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.security.auth.x500.X500Principal

class ApkAnalyzer(private val context: Context) {

    suspend fun analyzeApk(filePath: String): ApkInfo = withContext(Dispatchers.IO) {
        val file = File(filePath)
        val fileSize = file.length()
        val fileName = file.name

        val md5 = HashUtils.calculateMD5(file)
        val sha1 = HashUtils.calculateSHA1(file)
        val sha256 = HashUtils.calculateSHA256(file)

        var packageName = ""
        var versionName = ""
        var versionCode = 0L
        var minSdk = 0
        var targetSdk = 0
        var appLabel = ""
        val permissions = mutableListOf<String>()
        val activities = mutableListOf<ComponentInfo>()
        val services = mutableListOf<ComponentInfo>()
        val receivers = mutableListOf<ComponentInfo>()
        val providers = mutableListOf<ComponentInfo>()
        var certificateInfo: CertificateInfo? = null
        val nativeLibs = mutableListOf<String>()
        val resources = mutableListOf<ResourceInfo>()
        var manifestXml = ""
        var iconBytes: ByteArray? = null

        try {
            ZipFile(file).use { zip ->
                // Extract AndroidManifest.xml as raw
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                if (manifestEntry != null) {
                    manifestXml = "Binary AndroidManifest.xml (size: ${manifestEntry.size} bytes)"
                }

                // List resources
                zip.entries().asSequence().forEach { entry ->
                    when {
                        entry.name.startsWith("lib/") && entry.name.endsWith(".so") -> {
                            nativeLibs.add(entry.name)
                        }
                        entry.name.startsWith("res/") -> {
                            resources.add(ResourceInfo(entry.name, "resource", entry.size))
                        }
                        entry.name == "META-INF/MANIFEST.MF" -> {}
                        entry.name.startsWith("META-INF/") && (entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA") || entry.name.endsWith(".EC")) -> {
                            try {
                                certificateInfo = parseCertificate(zip.getInputStream(entry).readBytes())
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Try to get icon
                val iconEntry = zip.entries().asSequence()
                    .filter { it.name.startsWith("res/") && it.name.contains("ic_launcher") && it.name.endsWith(".png") }
                    .minByOrNull { it.name.length }
                if (iconEntry != null) {
                    iconBytes = zip.getInputStream(iconEntry).readBytes()
                }
            }
        } catch (_: Exception) {}

        // Use PackageManager for detailed info if possible
        try {
            val pm = context.packageManager
            val archiveInfo = pm.getPackageArchiveInfo(filePath,
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_META_DATA
            )

            archiveInfo?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = filePath
                appInfo.publicSourceDir = filePath
                appLabel = pm.getApplicationLabel(appInfo).toString()
            }

            archiveInfo?.let { info ->
                packageName = info.packageName ?: ""
                versionName = info.versionName ?: ""
                versionCode = info.longVersionCode

                info.applicationInfo?.let { appInfo ->
                    minSdk = appInfo.minSdkVersion
                    targetSdk = appInfo.targetSdkVersion
                }

                info.requestedPermissions?.forEach { perm ->
                    permissions.add(perm ?: "")
                }

                info.activities?.forEach { act ->
                    activities.add(ComponentInfo(
                        name = act.name ?: "",
                        exported = act.exported,
                        permission = act.permission
                    ))
                }

                info.services?.forEach { svc ->
                    services.add(ComponentInfo(
                        name = svc.name ?: "",
                        exported = svc.exported,
                        permission = svc.permission
                    ))
                }

                info.receivers?.forEach { rcv ->
                    receivers.add(ComponentInfo(
                        name = rcv.name ?: "",
                        exported = rcv.exported,
                        permission = rcv.permission
                    ))
                }

                info.providers?.forEach { prv ->
                    providers.add(ComponentInfo(
                        name = prv.name ?: "",
                        exported = prv.exported,
                        permission = prv.readPermission ?: prv.writePermission
                    ))
                }
            }
        } catch (_: Exception) {}

        ApkInfo(
            filePath = filePath,
            fileName = fileName,
            fileSize = fileSize,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            appLabel = appLabel,
            permissions = permissions,
            activities = activities,
            services = services,
            receivers = receivers,
            providers = providers,
            certificateInfo = certificateInfo,
            md5 = md5,
            sha1 = sha1,
            sha256 = sha256,
            manifestXml = manifestXml,
            iconBytes = iconBytes,
            nativeLibraries = nativeLibs,
            resources = resources
        )
    }

    private fun parseCertificate(certData: ByteArray): CertificateInfo? {
        return try {
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            val certs = cf.generateCertificates(certData.inputStream())
            val cert = certs.firstOrNull() as? X509Certificate ?: return null

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            CertificateInfo(
                subject = cert.subjectX500Principal.getName(X500Principal.RFC1779),
                issuer = cert.issuerX500Principal.getName(X500Principal.RFC1779),
                serialNumber = cert.serialNumber.toString(16),
                validFrom = dateFormat.format(cert.notBefore),
                validUntil = dateFormat.format(cert.notAfter),
                signatureAlgorithm = cert.sigAlgName
            )
        } catch (_: Exception) {
            null
        }
    }

    fun formatFileSize(size: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var s = size.toDouble()
        var unitIndex = 0
        while (s >= 1024 && unitIndex < units.size - 1) {
            s /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", s, units[unitIndex])
    }
}
