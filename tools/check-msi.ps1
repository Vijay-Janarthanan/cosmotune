# Checks the built Windows installer: it must install for all users, let the person choose the install folder, close a running
# Cosmotune before replacing its files and add a desktop shortcut.
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

$allUsers = Rows "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ALLUSERS'"
Write-Host "ALLUSERS: $($allUsers -join ', ')"
$rmShutdown = Rows "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'MSIRMSHUTDOWN'"
Write-Host "MSIRMSHUTDOWN: $($rmShutdown -join ', ')"

$failed = $false
# For all users: a per-user installer cannot write to Program Files, which the folder dialog offers (1.0.23, error 1303).
if (-not ($allUsers -contains '1')) { Write-Host 'The installer does not install for all users.'; $failed = $true }
# Closes a running Cosmotune first: otherwise its files are kept, set to be deleted at restart, and the restart deletes the new
# version's files of the same names (1.0.25 installed over a running 1.0.24).
if (-not ($rmShutdown -contains '1')) { Write-Host 'The installer does not close a running Cosmotune before replacing its files.'; $failed = $true }
if (-not ($shortcuts -contains 'DesktopFolder')) { Write-Host 'No desktop shortcut in the installer.'; $failed = $true }
if (-not ($dialogs | Where-Object { $_ -match 'InstallDir|Browse' })) { Write-Host 'The installer does not ask where to install.'; $failed = $true }
if ($failed) { exit 1 }
Write-Host 'The installer installs for all users, asks where to install, closes a running Cosmotune first and adds a desktop shortcut.'
