package com.cosmotune.desktop.update

import java.io.File

/**
 * The PowerShell script that finishes a Windows update after Cosmotune has quit: it waits for the app's process to end (the tray
 * included, so the single-instance lock is free), installs the downloaded MSI quietly into the folder Cosmotune is installed in,
 * writes how it went to the result file ([UpdateResult]) and opens Cosmotune again, the new one or, when the install failed, the
 * old one, which Windows Installer leaves in place. A folder this user cannot change (such as Program Files) is installed through
 * Windows' permission prompt.
 */
internal object WindowsUpdateScript {
    fun text(
        pid: Long,
        msi: File,
        install: DesktopInstall.WindowsInstalled,
        resultFile: File,
        log: File,
        installer: String = "msiexec.exe",
    ): String {
        val elevate = if (install.writable) "" else " -Verb RunAs"
        return """
            |${'$'}ErrorActionPreference = 'Continue'
            |${'$'}msi = ${quote(msi.path)}
            |${'$'}dir = ${quote(install.dir.path.trimEnd('\\'))}
            |${'$'}exe = ${quote(install.exe.path)}
            |${'$'}result = ${quote(resultFile.path)}
            |${'$'}log = ${quote(log.path)}
            |${'$'}installer = ${quote(installer)}
            |try { Wait-Process -Id $pid -Timeout 120 -ErrorAction SilentlyContinue } catch {}
            |Start-Sleep -Milliseconds 500
            |${'$'}arguments = @('/i', ('"' + ${'$'}msi + '"'), '/qn', '/norestart', ('INSTALLDIR="' + ${'$'}dir + '"'), '/l*v', ('"' + ${'$'}log + '"'))
            |try {
            |    ${'$'}process = Start-Process -FilePath ${'$'}installer -ArgumentList ${'$'}arguments -Wait -PassThru$elevate
            |    ${'$'}code = ${'$'}process.ExitCode
            |} catch {
            |    ${'$'}code = 1223
            |}
            |if (${'$'}code -eq 0 -or ${'$'}code -eq 3010) {
            |    Set-Content -LiteralPath ${'$'}result -Value 'ok'
            |    Remove-Item -LiteralPath ${'$'}msi -ErrorAction SilentlyContinue
            |} else {
            |    Set-Content -LiteralPath ${'$'}result -Value ('failed ' + ${'$'}code)
            |}
            |Start-Process -FilePath ${'$'}exe
            |""".trimMargin()
    }

    /** Writes [script] next to the download and starts it on its own, so it outlives this process. */
    fun launch(script: String, workDir: File) {
        workDir.mkdirs()
        val file = File(workDir, "cosmotune-update.ps1")
        file.writeText(script)
        ProcessBuilder(
            "cmd", "/c", "start", "\"\"", "/min",
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", file.path,
        ).directory(workDir).start()
    }

    /** A PowerShell literal: single quotes, an apostrophe doubled. */
    private fun quote(value: String) = "'" + value.replace("'", "''") + "'"
}

/** The line an update script leaves for the next launch: `ok`, or `failed <why>`. */
internal object UpdateResult {
    /** The failure to show once ("The update didn't install…"), or null; the file is removed either way. */
    fun take(file: File, currentVersion: String): String? {
        if (!file.isFile) return null
        val line = runCatching { file.readText().trim() }.getOrDefault("")
        file.delete()
        return if (line.startsWith("failed")) "The update didn't install. Cosmotune is still on $currentVersion." else null
    }
}
