<#
.SYNOPSIS
    Free My TV – Build & Deploy for Windows
    Connects to a TV over wireless ADB, clones a branch from GitHub, builds the APK and installs it.
    The terminal stays open so you can rebuild and reinstall without reconnecting.
#>

$ErrorActionPreference = 'Stop'
$Host.UI.RawUI.WindowTitle = 'Free My TV – Build & Deploy'

$REPO_URL = 'https://github.com/Dyterul/free-my-tv.git'
$WORK_DIR = Join-Path $env:TEMP 'free-my-tv-deploy'

# ─── Helpers ────────────────────────────────────────────────────────────────────

function Write-Step($msg) { Write-Host "`n>> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "   $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "   $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "   $msg" -ForegroundColor Red }

function Find-Adb {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if ($adb) { return $adb.Source }

    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
        "$env:ANDROID_HOME\platform-tools\adb.exe"
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
        "${env:ProgramFiles(x86)}\Android\android-sdk\platform-tools\adb.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

function Select-WithArrows {
    param(
        [string]$Title,
        [string[]]$Options
    )
    if ($Options.Count -eq 0) {
        Write-Err 'Keine Optionen verfuegbar.'
        return $null
    }

    Write-Host "`n$Title" -ForegroundColor Cyan
    Write-Host '(Pfeiltasten zum Navigieren, Enter zum Auswaehlen)' -ForegroundColor DarkGray

    $selected = 0
    $cursorTop = [Console]::CursorTop

    # Draw the list once
    for ($i = 0; $i -lt $Options.Count; $i++) {
        if ($i -eq $selected) {
            Write-Host "  > $($Options[$i])" -ForegroundColor Yellow
        } else {
            Write-Host "    $($Options[$i])"
        }
    }

    while ($true) {
        $key = [Console]::ReadKey($true)

        switch ($key.Key) {
            'UpArrow' {
                if ($selected -gt 0) { $selected-- }
            }
            'DownArrow' {
                if ($selected -lt $Options.Count - 1) { $selected++ }
            }
            'Enter' {
                # Move cursor past the list
                [Console]::SetCursorPosition(0, $cursorTop + $Options.Count)
                Write-Ok "Gewaehlt: $($Options[$selected])"
                return $Options[$selected]
            }
        }

        # Redraw the list
        [Console]::SetCursorPosition(0, $cursorTop)
        for ($i = 0; $i -lt $Options.Count; $i++) {
            $line = if ($i -eq $selected) { "  > $($Options[$i])" } else { "    $($Options[$i])" }
            # Pad to clear previous content
            $padded = $line.PadRight([Console]::WindowWidth - 1)
            if ($i -eq $selected) {
                Write-Host $padded -ForegroundColor Yellow -NoNewline
            } else {
                Write-Host $padded -NoNewline
            }
            Write-Host ''
        }
    }
}

# ─── ADB finden ─────────────────────────────────────────────────────────────────

Write-Host @'

  ╔═══════════════════════════════════╗
  ║   Free My TV – Build & Deploy    ║
  ╚═══════════════════════════════════╝

'@ -ForegroundColor Cyan

$ADB = Find-Adb
if (-not $ADB) {
    Write-Err 'adb nicht gefunden. Bitte Android SDK Platform-Tools installieren und zum PATH hinzufuegen.'
    Write-Host '   https://developer.android.com/tools/releases/platform-tools' -ForegroundColor DarkGray
    Read-Host 'Enter zum Beenden'
    exit 1
}
Write-Ok "adb gefunden: $ADB"

# ─── Verbindung zum TV ──────────────────────────────────────────────────────────

function Connect-TV {
    Write-Step 'Verbindung zum Fernseher'

    # Scan for mDNS devices (adb mdns check)
    $mdnsAvailable = $false
    try {
        $mdnsCheck = & $ADB mdns check 2>&1
        if ($mdnsCheck -match 'mdns daemon running') { $mdnsAvailable = $true }
    } catch {}

    $devices = @()
    if ($mdnsAvailable) {
        Write-Host '   Suche nach Wireless-ADB-Geraeten (5 Sekunden)...' -ForegroundColor DarkGray
        $mdnsServices = & $ADB mdns services 2>&1
        $lines = $mdnsServices -split "`n" | Where-Object { $_ -match '_adb-tls' }
        foreach ($line in $lines) {
            if ($line -match '(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d+)') {
                $devices += "$($Matches[1]):$($Matches[2])"
            }
        }
    }

    # Also check already-connected devices
    $connected = & $ADB devices 2>&1 | Select-String -Pattern '^\S+\s+device$'
    foreach ($d in $connected) {
        $addr = ($d -split '\s+')[0]
        if ($addr -and $devices -notcontains $addr) {
            $devices += $addr
        }
    }

    if ($devices.Count -gt 0) {
        Write-Ok "$($devices.Count) Geraet(e) gefunden:"
        $choices = $devices + @('Manuell eingeben')
        $pick = Select-WithArrows -Title 'Welches Geraet?' -Options $choices
        if ($pick -eq 'Manuell eingeben') {
            $target = Read-Host '   IP:Port eingeben (z.B. 192.168.1.100:5555)'
        } else {
            $target = $pick
        }
    } else {
        if ($mdnsAvailable) {
            Write-Warn 'Keine Geraete gefunden.'
        } else {
            Write-Warn 'mDNS nicht verfuegbar – Geraetesuche uebersprungen.'
        }
        $target = Read-Host '   IP:Port eingeben (z.B. 192.168.1.100:5555)'
    }

    if ($target -notmatch ':\d+$') { $target = "${target}:5555" }

    # Wireless debugging pairing?
    $pairChoice = Read-Host '   Muss zuerst gepairt werden? (j/n, Standard: n)'
    if ($pairChoice -eq 'j') {
        $pairAddr = Read-Host '   Pairing-Adresse:Port (vom TV-Bildschirm)'
        $pairCode = Read-Host '   Pairing-Code (vom TV-Bildschirm)'
        Write-Host "   Pairing mit $pairAddr ..." -ForegroundColor DarkGray
        $pairResult = & $ADB pair $pairAddr $pairCode 2>&1
        Write-Host "   $pairResult"
        if ($pairResult -notmatch 'Successfully paired') {
            Write-Err 'Pairing fehlgeschlagen.'
            return $null
        }
        Write-Ok 'Pairing erfolgreich.'
    }

    Write-Host "   Verbinde mit $target ..." -ForegroundColor DarkGray
    $connectResult = & $ADB connect $target 2>&1
    Write-Host "   $connectResult"

    if ($connectResult -match 'connected|already connected') {
        Write-Ok "Verbunden mit $target"
        return $target
    } else {
        Write-Err "Verbindung fehlgeschlagen: $connectResult"
        return $null
    }
}

# ─── Branch holen und auswaehlen ─────────────────────────────────────────────────

function Select-Branch {
    Write-Step 'Branches von GitHub laden'

    $branchOutput = git ls-remote --heads $REPO_URL 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Konnte Branches nicht laden: $branchOutput"
        return $null
    }

    $branches = @()
    foreach ($line in ($branchOutput -split "`n")) {
        if ($line -match 'refs/heads/(.+)$') {
            $branches += $Matches[1].Trim()
        }
    }

    if ($branches.Count -eq 0) {
        Write-Err 'Keine Branches gefunden.'
        return $null
    }

    # Put main first
    $sorted = @()
    if ($branches -contains 'main') {
        $sorted += 'main'
        $branches = $branches | Where-Object { $_ -ne 'main' }
    }
    $sorted += ($branches | Sort-Object)

    $branch = Select-WithArrows -Title 'Welcher Branch?' -Options $sorted
    return $branch
}

# ─── Clone und Build ─────────────────────────────────────────────────────────────

function Build-APK {
    param([string]$Branch)

    Write-Step "Branch '$Branch' klonen und bauen"

    $cloneDir = Join-Path $WORK_DIR $Branch
    if (Test-Path $cloneDir) {
        Write-Host '   Vorheriger Clone gefunden, aktualisiere...' -ForegroundColor DarkGray
        Push-Location $cloneDir
        try {
            git fetch origin $Branch 2>&1 | Out-Null
            git checkout $Branch 2>&1 | Out-Null
            git reset --hard "origin/$Branch" 2>&1 | Out-Null
            Write-Ok 'Aktualisiert.'
        } finally {
            Pop-Location
        }
    } else {
        Write-Host "   Klone $REPO_URL ($Branch) ..." -ForegroundColor DarkGray
        if (-not (Test-Path $WORK_DIR)) { New-Item -ItemType Directory -Path $WORK_DIR -Force | Out-Null }
        git clone --branch $Branch --single-branch $REPO_URL $cloneDir 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }
        if ($LASTEXITCODE -ne 0) {
            Write-Err 'Clone fehlgeschlagen.'
            return $null
        }
        Write-Ok 'Geklont.'
    }

    # config.properties?
    $configSrc = Join-Path $cloneDir 'config.properties'
    if (-not (Test-Path $configSrc)) {
        $configExample = Join-Path $cloneDir 'config.example.properties'
        if (Test-Path $configExample) {
            Copy-Item $configExample $configSrc
            Write-Warn "config.properties erstellt aus config.example.properties."
            Write-Host '   Du kannst sie spaeter bearbeiten: ' -NoNewline
            Write-Host $configSrc -ForegroundColor Yellow
        }
    }

    # Build
    Write-Host '   Baue APK...' -ForegroundColor DarkGray
    Push-Location $cloneDir
    try {
        # build.sh runs under Git Bash on Windows
        $gitBash = $null
        $gitPaths = @(
            "${env:ProgramFiles}\Git\bin\bash.exe"
            "${env:ProgramFiles(x86)}\Git\bin\bash.exe"
            "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
        )
        foreach ($p in $gitPaths) {
            if (Test-Path $p) { $gitBash = $p; break }
        }
        if (-not $gitBash) {
            $gitBash = (Get-Command bash -ErrorAction SilentlyContinue).Source
        }
        if (-not $gitBash) {
            Write-Err 'bash (Git Bash) nicht gefunden. Bitte Git for Windows installieren.'
            return $null
        }

        $buildResult = & $gitBash -c './build.sh' 2>&1
        $buildResult | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }

        $apk = Join-Path $cloneDir 'FreeMyTV.apk'
        if (Test-Path $apk) {
            $size = [math]::Round((Get-Item $apk).Length / 1MB, 1)
            Write-Ok "APK gebaut: FreeMyTV.apk (${size} MB)"
            return $apk
        } else {
            Write-Err 'Build fehlgeschlagen – keine APK erstellt.'
            return $null
        }
    } finally {
        Pop-Location
    }
}

# ─── Installieren ────────────────────────────────────────────────────────────────

function Install-APK {
    param([string]$APKPath, [string]$Device)

    Write-Step 'APK auf den Fernseher installieren'
    Write-Host "   Installiere auf $Device ..." -ForegroundColor DarkGray
    Write-Warn 'Falls der TV fragt (Play Protect / Unbekannte App): auf dem TV "Trotzdem installieren" waehlen.'

    $installResult = & $ADB -s $Device install -r $APKPath 2>&1
    Write-Host "   $installResult"

    if ($installResult -match 'Success') {
        Write-Ok 'Installation erfolgreich!'

        # TCL auto-start permission
        $brand = & $ADB -s $Device shell getprop ro.product.brand 2>&1
        if ($brand -match 'TCL') {
            Write-Host '   TCL erkannt – setze Auto-Start-Berechtigung...' -ForegroundColor DarkGray
            & $ADB -s $Device shell "appops set io.freemytv AUTO_START allow 2>/dev/null; appops set io.freemytv APP_AUTO_START allow 2>/dev/null" 2>&1 | Out-Null
            Write-Ok 'TCL Auto-Start gesetzt.'
        }
        return $true
    } else {
        Write-Err 'Installation fehlgeschlagen.'
        return $false
    }
}

# ─── Hauptprogramm ──────────────────────────────────────────────────────────────

$tvDevice = $null

# First connection
$tvDevice = Connect-TV
if (-not $tvDevice) {
    Write-Err 'Keine Verbindung zum TV. Starte trotzdem – du kannst es spaeter erneut versuchen.'
}

# Main loop
while ($true) {
    Write-Host "`n" -NoNewline
    Write-Host '═══════════════════════════════════════' -ForegroundColor DarkGray

    $actions = @()
    if ($tvDevice) {
        $actions += 'Branch waehlen, bauen & installieren'
        $actions += 'Nur installieren (vorhandene APK)'
        $actions += 'TV neu verbinden'
    } else {
        $actions += 'TV verbinden'
    }
    $actions += 'Beenden'

    $action = Select-WithArrows -Title 'Was moechtest du tun?' -Options $actions

    switch ($action) {
        'Branch waehlen, bauen & installieren' {
            $branch = Select-Branch
            if (-not $branch) { continue }
            $apk = Build-APK -Branch $branch
            if (-not $apk) { continue }

            # Disconnect before they switch on TV (see CLAUDE.md step 8)
            Write-Warn "Trenne $tvDevice vor der Installation (damit der TV sein ADB-Prompt richtig zeigt)."
            & $ADB disconnect $tvDevice 2>&1 | Out-Null
            Start-Sleep -Seconds 1
            $reconnect = & $ADB connect $tvDevice 2>&1
            if ($reconnect -notmatch 'connected') {
                Write-Err "Neuverbindung fehlgeschlagen: $reconnect"
                $tvDevice = $null
                continue
            }

            Install-APK -APKPath $apk -Device $tvDevice
        }
        'Nur installieren (vorhandene APK)' {
            $existingApks = @()
            if (Test-Path $WORK_DIR) {
                $existingApks = Get-ChildItem -Path $WORK_DIR -Recurse -Filter 'FreeMyTV.apk' | ForEach-Object { $_.FullName }
            }
            if ($existingApks.Count -eq 0) {
                Write-Err 'Keine APKs gefunden. Bitte zuerst einen Branch bauen.'
                continue
            }
            $apkPick = if ($existingApks.Count -eq 1) {
                $existingApks[0]
            } else {
                Select-WithArrows -Title 'Welche APK?' -Options $existingApks
            }
            Install-APK -APKPath $apkPick -Device $tvDevice
        }
        'TV verbinden' {
            $tvDevice = Connect-TV
        }
        'TV neu verbinden' {
            if ($tvDevice) {
                & $ADB disconnect $tvDevice 2>&1 | Out-Null
            }
            $tvDevice = Connect-TV
        }
        'Beenden' {
            if ($tvDevice) {
                Write-Step 'Aufraeumen'
                & $ADB disconnect $tvDevice 2>&1 | Out-Null
                Write-Ok 'TV getrennt.'
            }
            Write-Host "`nBye!" -ForegroundColor Cyan
            exit 0
        }
    }
}
