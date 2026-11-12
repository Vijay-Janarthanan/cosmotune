# Checks the built Windows installer: it must let the person choose the install folder and must add a desktop shortcut.
# Usage: ./tools/check-msi.ps1 -Msi <path to Cosmotune.msi>
param([Parameter(Mandatory = $true)][string]$Msi)

$installer = New-Object -ComObject WindowsInstaller.Installer
$db = $installer.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $installer, @($Msi, 0))

# The values of one column, or nothing when the installer has no such table (an installer without dialogs has no Dialog table).
function Rows([string]$sql) {
    try { $view = $db.GetType().InvokeMember('OpenView', 'InvokeMethod', $null, $db, @($sql)) } catch { return @() }
    $view.GetType().InvokeMember('Execute', 'InvokeMethod', $null, $view, $null) | Out-Null
    $rows = @()
    while ($true) {
        $record = $view.GetType().InvokeMember('Fetch', 'InvokeMethod', $null, $view, $null)
        if ($null -eq $record) { break }
        $rows += $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, 1)
    }
    $view.GetType().InvokeMember('Close', 'InvokeMethod', $null, $view, $null) | Out-Null
    return $rows
}

$shortcuts = Rows 'SELECT `Directory_` FROM `Shortcut`'
$dialogs = Rows 'SELECT `Dialog` FROM `Dialog`'
Write-Host "Shortcut folders: $($shortcuts -join ', ')"
Write-Host "Installer dialogs: $($dialogs -join ', ')"

$failed = $false
if (-not ($shortcuts -contains 'DesktopFolder')) { Write-Host 'No desktop shortcut in the installer.'; $failed = $true }
if (-not ($dialogs | Where-Object { $_ -match 'InstallDir|Browse' })) { Write-Host 'The installer does not ask where to install.'; $failed = $true }
if ($failed) { exit 1 }
Write-Host 'The installer asks where to install and adds a desktop shortcut.'
