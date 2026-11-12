package com.cosmotune.desktop.update

import java.io.File

/**
 * The bash script that finishes a Mac update after Cosmotune has quit: it waits for the app's process to end, unpacks the
 * downloaded zip into a temporary folder, clears the "downloaded from the internet" flag on the new bundle, swaps it in for the
 * old one ([SWAP]: the old one is moved aside and moved back if the new one cannot take its place), writes how it went to the
 * result file ([UpdateResult]) and opens the bundle again. A bundle this user cannot change (an old .pkg install, owned by the
 * system) is swapped through macOS's password prompt and handed to the user, so the next update needs no password.
 */
internal object MacUpdateScript {
    /** Swaps `$2` in for `$1`, then gives it to `$3` when set. Exit 10/11: the old bundle stayed. */
    val SWAP = """
        |set -u
        |bundle="$1"; fresh="$2"; owner="$3"
        |old="${'$'}bundle.old"
        |rm -rf "${'$'}old"
        |mv "${'$'}bundle" "${'$'}old" || exit 10
        |if ! mv "${'$'}fresh" "${'$'}bundle"; then mv "${'$'}old" "${'$'}bundle"; exit 11; fi
        |rm -rf "${'$'}old"
        |if [ -n "${'$'}owner" ]; then chown -R "${'$'}owner" "${'$'}bundle"; fi
        |exit 0
        |""".trimMargin()

    fun text(pid: Long, zip: File, install: DesktopInstall.MacBundle, resultFile: File, log: File, opener: String = "open"): String {
        val swapCall = if (install.writable) {
            "/bin/bash \"${'$'}swap\" \"${'$'}bundle\" \"${'$'}fresh\" \"\""
        } else {
            // AppleScript's quoted form keeps every path whole; the password prompt names Cosmotune's helper.
            "/usr/bin/osascript -e \"do shell script \\\"/bin/bash \\\" & quoted form of \\\"${'$'}swap\\\" & \\\" \\\" & quoted form of \\\"${'$'}bundle\\\" & \\\" \\\" & quoted form of \\\"${'$'}fresh\\\" & \\\" \\\" & quoted form of \\\"${'$'}me\\\" with administrator privileges\""
        }
        return """
            |#!/bin/bash
            |zip=${quote(zip.invariantSeparatorsPath)}
            |bundle=${quote(install.bundle.invariantSeparatorsPath)}
            |result=${quote(resultFile.invariantSeparatorsPath)}
            |log=${quote(log.invariantSeparatorsPath)}
            |opener=${quote(opener)}
            |me="${'$'}(id -un)"
            |exec >>"${'$'}log" 2>&1
            |waited=0
            |while kill -0 $pid 2>/dev/null && [ ${'$'}waited -lt 120 ]; do sleep 1; waited=${'$'}((waited + 1)); done
            |sleep 1
            |stage="${'$'}(mktemp -d "${'$'}{TMPDIR:-/tmp}/cosmotune-update.XXXXXX")"
            |swap="${'$'}stage/swap.sh"
            |cat > "${'$'}swap" <<'COSMOTUNE_SWAP'
            |$SWAP
            |COSMOTUNE_SWAP
            |fresh="${'$'}stage/Cosmotune.app"
            |if ! /usr/bin/ditto -x -k "${'$'}zip" "${'$'}stage" || [ ! -d "${'$'}fresh" ]; then
            |    echo "failed unpack" > "${'$'}result"; "${'$'}opener" "${'$'}bundle"; exit 1
            |fi
            |/usr/bin/xattr -dr com.apple.quarantine "${'$'}fresh" 2>/dev/null
            |$swapCall
            |code=${'$'}?
            |if [ ${'$'}code -ne 0 ]; then
            |    echo "failed swap ${'$'}code" > "${'$'}result"
            |else
            |    echo "ok" > "${'$'}result"
            |    rm -f "${'$'}zip"
            |fi
            |"${'$'}opener" "${'$'}bundle"
            |rm -rf "${'$'}stage"
            |""".trimMargin()
    }

    /** Writes [script] next to the download and starts it detached, so it outlives this process. */
    fun launch(script: String, workDir: File) {
        workDir.mkdirs()
        val file = File(workDir, "cosmotune-update.sh")
        file.writeText(script)
        ProcessBuilder("/bin/bash", "-c", "nohup /bin/bash ${quote(file.path)} >/dev/null 2>&1 &").directory(workDir).start().waitFor()
    }

    /** A bash literal: single quotes, an apostrophe written as '\''. */
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
