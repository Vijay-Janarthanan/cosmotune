package com.cosmotune.desktop.update

import java.io.File

/**
 * The PowerShell script that finishes a Windows update after Cosmotune has quit. It waits for the app's process to end (the tray
 * included, so the single-instance lock is free), installs through Windows' permission prompt (Windows Installer's own), writes how
 * it went to the result file ([UpdateResult]) and opens Cosmotune again: the new one or, when the install failed or the prompt was
 * declined, the old one, which is still in place. Every step goes to `steps`, so an update that did not come back can be read
 * afterwards.
 *
 * Cosmotune installs for all users (from 1.0.25). A copy installed for this user only (1.0.23 and 1.0.24) cannot be upgraded by an
 * all-users installer, which looks only for all-users copies; Windows Installer says which kind each installed copy is (its
 * `AssignmentType`). Such a copy is removed first and the new one installed into the same folder, both behind one prompt (the script
 * runs itself again with `-Install`): installing first and removing after would take the new copy's files with the old one when the
 * folder is the same.
 */
internal object WindowsUpdateScript {
    /** The installer's upgrade code: `upgradeUuid` in desktopApp/build.gradle.kts. */
    const val UPGRADE_CODE = "{D4F2B6A1-3C58-4E7F-9A21-6B0C8E5D7F34}"

    fun text(
        pid: Long,
        msi: File,
        install: DesktopInstall.WindowsInstalled,
        resultFile: File,
        log: File,
        steps: File,
        upgradeCode: String? = UPGRADE_CODE,
        legacyProductCode: String? = null,
        installer: String = "msiexec.exe",
        elevate: Boolean = true,
    ): String {
        val runAs = if (elevate) " -Verb RunAs" else ""
        return """
            |param([switch]${'$'}Install, [string]${'$'}Legacy)
            |${'$'}ErrorActionPreference = 'Continue'
            |${'$'}msi = ${quote(msi.path)}
            |${'$'}dir = ${quote(install.dir.path.trimEnd('\\'))}
            |${'$'}exe = ${quote(install.exe.path)}
            |${'$'}result = ${quote(resultFile.path)}
            |${'$'}log = ${quote(log.path)}
            |${'$'}steps = ${quote(steps.path)}
            |${'$'}installer = ${quote(installer)}
            |${'$'}upgradeCode = ${upgradeCode?.let(::quote) ?: "\$null"}
            |function Step([string]${'$'}line) {
            |    try { Add-Content -LiteralPath ${'$'}steps -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + ${'$'}line) -Encoding UTF8 } catch {}
            |}
            |${'$'}arguments = @('/i', ('"' + ${'$'}msi + '"'), '/qn', '/norestart', ('INSTALLDIR="' + ${'$'}dir + '"'), '/l*v', ('"' + ${'$'}log + '"'))
            |
            |if (${'$'}Install) {
            |    # Behind the one permission prompt: remove the copy for this user only, then install for all users into its folder.
            |    Step ('removing the copy for this user only: ' + ${'$'}Legacy)
            |    ${'$'}removal = Start-Process -FilePath ${'$'}installer -ArgumentList @('/x', ${'$'}Legacy, '/qn', '/norestart', '/l*v', ('"' + ${'$'}log + '.remove.log"')) -Wait -PassThru
            |    Step ('removed: ' + ${'$'}removal.ExitCode)
            |    if (${'$'}removal.ExitCode -ne 0 -and ${'$'}removal.ExitCode -ne 3010 -and ${'$'}removal.ExitCode -ne 1605) { exit ${'$'}removal.ExitCode }
            |    ${'$'}process = Start-Process -FilePath ${'$'}installer -ArgumentList ${'$'}arguments -Wait -PassThru
            |    exit ${'$'}process.ExitCode
            |}
            |
            |Step 'waiting for Cosmotune to close'
            |try { Wait-Process -Id $pid -Timeout 120 -ErrorAction Stop } catch { Step ('wait: ' + ${'$'}_.Exception.Message) }
            |Start-Sleep -Milliseconds 500
            |${'$'}legacy = ${legacyProductCode?.let(::quote) ?: "\$null"}
            |if (-not ${'$'}legacy -and ${'$'}upgradeCode) {
            |    # Windows Installer says which kind each installed copy is: 1 for all users, otherwise for this user only.
            |    try {
            |        ${'$'}wi = New-Object -ComObject WindowsInstaller.Installer
            |        foreach (${'$'}code in ${'$'}wi.GetType().InvokeMember('RelatedProducts', 'GetProperty', ${'$'}null, ${'$'}wi, @(${'$'}upgradeCode))) {
            |            ${'$'}kind = ${'$'}wi.GetType().InvokeMember('ProductInfo', 'GetProperty', ${'$'}null, ${'$'}wi, @(${'$'}code, 'AssignmentType'))
            |            Step ('installed copy ' + ${'$'}code + ': ' + ${'$'}(if (${'$'}kind -eq '1') { 'for all users' } else { 'for this user only' }))
            |            if (${'$'}kind -ne '1') { ${'$'}legacy = ${'$'}code }
            |        }
            |    } catch { Step ('could not list the installed copies: ' + ${'$'}_.Exception.Message) }
            |}
            |Step 'installing'
            |try {
            |    if (${'$'}legacy) {
            |        ${'$'}self = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', ('"' + ${'$'}PSCommandPath + '"'), '-Install', '-Legacy', ${'$'}legacy)
            |        ${'$'}process = Start-Process -FilePath 'powershell.exe' -ArgumentList ${'$'}self -Wait -PassThru$runAs -ErrorAction Stop
            |    } else {
            |        ${'$'}process = Start-Process -FilePath ${'$'}installer -ArgumentList ${'$'}arguments -Wait -PassThru$runAs -ErrorAction Stop
            |    }
            |    ${'$'}code = ${'$'}process.ExitCode
            |} catch {
            |    Step ('the install did not start: ' + ${'$'}_.Exception.Message)
            |    ${'$'}code = 1223
            |}
            |Step ('install finished: ' + ${'$'}code)
            |if (${'$'}code -eq 0 -or ${'$'}code -eq 3010) {
            |    Set-Content -LiteralPath ${'$'}result -Value 'ok'
            |    Remove-Item -LiteralPath ${'$'}msi -ErrorAction SilentlyContinue
            |} else {
            |    Set-Content -LiteralPath ${'$'}result -Value ('failed ' + ${'$'}code)
            |}
            |Step ('opening ' + ${'$'}exe)
            |try {
            |    Start-Process -FilePath ${'$'}exe -WorkingDirectory ${'$'}dir -ErrorAction Stop
            |    Step 'opened'
            |} catch {
            |    Step ('could not open it: ' + ${'$'}_.Exception.Message)
            |    try {
            |        Start-Process -FilePath 'explorer.exe' -ArgumentList ('"' + ${'$'}exe + '"') -ErrorAction Stop
            |        Step 'opened through Explorer'
            |    } catch {
            |        Step ('Explorer could not open it either: ' + ${'$'}_.Exception.Message)
            |    }
            |}
            |""".trimMargin()
    }

    /** Writes [script] next to the download and starts it on its own, so it outlives this process. */
    fun launch(script: String, workDir: File) {
        val file = write(script, workDir)
        ProcessBuilder(
            "cmd", "/c", "start", "\"\"", "/min",
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", file.path,
        ).directory(workDir).start()
    }

    /**
     * The script file as Windows PowerShell 5.1 will read it: UTF-8 with its byte-order mark, which it needs to read the file as
     * UTF-8 rather than the ANSI code page, so a folder named "José" or in Tamil reaches msiexec and the relaunch intact.
     */
    fun write(script: String, workDir: File): File {
        workDir.mkdirs()
        val text = "﻿" + script
        // The usual name first; if Windows refuses it ("Access is denied" was seen for a file that was gone a moment later), a name
        // of its own next to it, then the temporary folder, so one stuck file cannot stop the update. Why it was refused is logged.
        val tries = listOf(File(workDir, "cosmotune-update.ps1"), File(workDir, "cosmotune-update-${System.currentTimeMillis()}.ps1"),
            File(System.getProperty("java.io.tmpdir"), "cosmotune-update-${System.currentTimeMillis()}.ps1"))
        var failure: Exception? = null
        for (file in tries) {
            try {
                file.writeText(text, Charsets.UTF_8)
                if (failure != null) com.cosmotune.music.desktop.DesktopTrackLog.log("update: wrote the install script as ${file.name} in ${file.parentFile}")
                return file
            } catch (e: java.io.IOException) {
                com.cosmotune.music.desktop.DesktopTrackLog.log("update: could not write ${file.path} (${e.message}; exists=${file.exists()}, " +
                    "read-only=${file.exists() && !file.canWrite()}, folder writable=${file.parentFile?.canWrite()})")
                failure = e
            }
        }
        throw failure!!
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
