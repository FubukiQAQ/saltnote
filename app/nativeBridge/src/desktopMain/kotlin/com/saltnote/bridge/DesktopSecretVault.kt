package com.saltnote.bridge

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * OS-backed Desktop secret vault:
 * - Windows: DPAPI (`Crypt32Util.cryptProtectData` / `cryptUnprotectData`) stored outside the synced Vault
 * - macOS: Keychain via `/usr/bin/security`
 * - Linux: libsecret via `secret-tool`
 */
class DesktopSecretVault : SecretVault {
    private val osName = System.getProperty("os.name").lowercase()
    private val secretsDir: File by lazy {
        val base = when {
            osName.contains("win") -> System.getenv("LOCALAPPDATA")
                ?: File(System.getProperty("user.home"), "AppData/Local").absolutePath
            osName.contains("mac") -> File(
                System.getProperty("user.home"),
                "Library/Application Support",
            ).absolutePath
            else -> System.getenv("XDG_DATA_HOME")
                ?: File(System.getProperty("user.home"), ".local/share").absolutePath
        }
        File(base, "SaltNote/secrets").apply { mkdirs() }
    }

    override fun getSecret(keyRef: String): String? {
        val id = normalizeKeyId(keyRef)
        return when {
            osName.contains("win") -> getWindowsDpapi(id)
            osName.contains("mac") -> getMacKeychain(id)
            else -> getLinuxSecretService(id)
        }
    }

    override fun putSecret(keyRef: String, secretValue: String) {
        val id = normalizeKeyId(keyRef)
        when {
            osName.contains("win") -> putWindowsDpapi(id, secretValue)
            osName.contains("mac") -> putMacKeychain(id, secretValue)
            else -> putLinuxSecretService(id, secretValue)
        }
    }

    override fun deleteSecret(keyRef: String) {
        val id = normalizeKeyId(keyRef)
        when {
            osName.contains("win") -> dpapiFile(id).delete()
            osName.contains("mac") -> runCmd("security", "delete-generic-password", "-s", SERVICE, "-a", id)
            else -> runCmd("secret-tool", "clear", "service", SERVICE, "account", id)
        }
    }

    private fun getWindowsDpapi(id: String): String? {
        val f = dpapiFile(id)
        if (!f.exists()) return null
        return runCatching {
            val encrypted = Base64.getDecoder().decode(f.readText(StandardCharsets.UTF_8).trim())
            val plain = Crypt32Util.cryptUnprotectData(encrypted)
            String(plain, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun putWindowsDpapi(id: String, value: String) {
        val encrypted = Crypt32Util.cryptProtectData(value.toByteArray(StandardCharsets.UTF_8))
        dpapiFile(id).writeText(Base64.getEncoder().encodeToString(encrypted), StandardCharsets.UTF_8)
    }

    private fun getMacKeychain(id: String): String? =
        runCmd("security", "find-generic-password", "-s", SERVICE, "-a", id, "-w")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun putMacKeychain(id: String, value: String) {
        runCmd("security", "add-generic-password", "-U", "-s", SERVICE, "-a", id, "-w", value)
    }

    private fun getLinuxSecretService(id: String): String? =
        runCmd("secret-tool", "lookup", "service", SERVICE, "account", id)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun putLinuxSecretService(id: String, value: String) {
        runCatching {
            val proc = ProcessBuilder(
                "secret-tool", "store", "--label=SaltNote $id", "service", SERVICE, "account", id,
            ).start()
            proc.outputStream.use { it.write(value.toByteArray(StandardCharsets.UTF_8)) }
            proc.waitFor()
        }
    }

    private fun dpapiFile(id: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        return File(secretsDir, "$digest.dpapi")
    }

    private fun normalizeKeyId(keyRef: String): String =
        keyRef.removePrefix("vault://").trim()

    private fun runCmd(vararg args: String): String? = runCatching {
        val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() == 0) out else null
    }.getOrNull()

    companion object {
        private const val SERVICE = "com.saltnote.byok"
    }
}
