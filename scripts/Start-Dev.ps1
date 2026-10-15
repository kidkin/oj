param(
    [ValidateSet('Start', 'Build', 'Stop', 'Status')]
    [string]$Action = 'Start',
    [switch]$SkipBuild,
    [switch]$NoBrowser
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$deployRoot = Join-Path $repoRoot 'deploy'
$envFile = Join-Path $deployRoot '.env'
$composeFile = Join-Path $deployRoot 'compose.dev.yml'
$logRoot = Join-Path $repoRoot 'logs'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$logFile = Join-Path $logRoot ('dev-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
Start-Transcript -Path $logFile | Out-Null

function Invoke-Compose {
    param([string[]]$ComposeArguments)
    & $script:composeExe --project-directory $script:deployRoot --env-file $script:envFile -f $script:composeFile @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw ('Docker Compose failed, exit code ' + $LASTEXITCODE) }
}

function New-Secret {
    $bytes = New-Object byte[] 24
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

try {
    $dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
    $dockerExe = if ($dockerCommand) { $dockerCommand.Source } else { $null }
    $desktopRoot = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop'
    if (-not $dockerExe) {
        foreach ($candidate in @((Join-Path $desktopRoot 'resources\bin\docker.exe'), (Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin\docker.exe'))) {
            if (Test-Path -LiteralPath $candidate) { $dockerExe = $candidate; break }
        }
    }
    if (-not $dockerExe) { throw 'Install Docker Desktop with the WSL2 Linux engine first.' }
    Write-Host ('Using Docker: ' + $dockerExe)
    $dockerResources = Split-Path -Parent (Split-Path -Parent $dockerExe)
    $composeExe = Join-Path $dockerResources 'cli-plugins\docker-compose.exe'
    if (-not (Test-Path -LiteralPath $composeExe)) {
        $composeCommand = Get-Command docker-compose.exe -ErrorAction SilentlyContinue
        if (-not $composeCommand) { throw 'Docker Compose v2 or newer is required.' }
        $composeExe = $composeCommand.Source
    }
    # Running docker info without a daemon can otherwise hang indefinitely.
    $engineReady = $false
    $deadline = (Get-Date).AddMinutes(4)
    $desktopStarted = $false
    do {
        $probe = New-Object Diagnostics.Process
        $probe.StartInfo = New-Object Diagnostics.ProcessStartInfo
        $probe.StartInfo.FileName = $dockerExe
        $probe.StartInfo.Arguments = 'info --format {{.OSType}}'
        $probe.StartInfo.UseShellExecute = $false
        $probe.StartInfo.CreateNoWindow = $true
        $probe.StartInfo.RedirectStandardOutput = $true
        $probe.StartInfo.RedirectStandardError = $true
        try {
            [void]$probe.Start()
            $probeHandle = $probe.Handle
            if ($probe.WaitForExit(5000)) {
                $probeOutput = $probe.StandardOutput.ReadToEnd().Trim()
                $probeError = $probe.StandardError.ReadToEnd().Trim()
                $engineReady = $probe.ExitCode -eq 0 -and $probeOutput -eq 'linux'
                if (-not $engineReady) { Write-Host ('Docker probe exit=' + $probe.ExitCode + ', output=' + $probeOutput + ', error=' + $probeError) }
            } else { $probe.Kill(); Write-Host 'Docker engine probe timed out.' }
        } finally { $probe.Dispose() }
        if ($engineReady) { break }
        if (-not $desktopStarted) {
            $desktopExe = Join-Path (Split-Path -Parent $dockerResources) 'Docker Desktop.exe'
            if (Test-Path -LiteralPath $desktopExe) { Start-Process -FilePath $desktopExe -WindowStyle Hidden }
            $desktopStarted = $true
        }
        Write-Host 'Waiting for the Docker Linux engine...'
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    if (-not $engineReady) { throw 'Docker Linux engine is not ready. Check Docker Desktop.' }

    if (-not (Test-Path -LiteralPath $envFile)) {
        if ($Action -in @('Stop', 'Status')) { throw 'No development environment has been initialized.' }
        $envText = [IO.File]::ReadAllText((Join-Path $deployRoot '.env.example'))
        foreach ($name in @('MYSQL_ROOT_PASSWORD','REDIS_PASSWORD','NACOS_PASSWORD','JWT_TOKEN_SECRET','JUDGE_TOKEN')) {
            $envText = [regex]::Replace($envText, '(?m)^' + $name + '=.*$', $name + '=' + (New-Secret))
        }
        [IO.File]::WriteAllText($envFile, $envText, (New-Object Text.UTF8Encoding($false)))
        Write-Host 'Created deploy/.env with independent random service credentials.'
    }
    Invoke-Compose -ComposeArguments @('config', '--quiet')
    if ($Action -eq 'Stop') { Invoke-Compose -ComposeArguments @('stop') }
    elseif ($Action -eq 'Status') { Invoke-Compose -ComposeArguments @('ps', '--all') }
    else {
        $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
        $gitExe = if ($gitCommand) { $gitCommand.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\Git\cmd\git.exe' }
        if (Test-Path -LiteralPath $gitExe) {
            $revision = & $gitExe -C $repoRoot rev-parse --short=12 HEAD
            if ($LASTEXITCODE -eq 0) {
                $dirty = & $gitExe -C $repoRoot status --porcelain
                $env:SOURCE_REVISION = $revision + $(if ($dirty) { '-dirty' } else { '' })
            }
        }
        if (-not $SkipBuild -or $Action -eq 'Build') {
            Write-Host 'Building frontend, backend and judge from source. The first build downloads dependencies.'
            Invoke-Compose -ComposeArguments @('build')
        }
        if ($Action -eq 'Start') {
            Invoke-Compose -ComposeArguments @('up', '--detach', '--no-build', '--wait', '--wait-timeout', '360')
            $url = 'http://127.0.0.1:18081'
            $response = Invoke-WebRequest -Uri ($url + '/api/get-website-config') -UseBasicParsing -TimeoutSec 20
            $json = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray()) | ConvertFrom-Json
            if ($json.status -ne 200) { throw 'The application API is not ready.' }
            Write-Host ('Source-built HOJ is ready: ' + $url)
            Write-Host 'Development data belongs to the separate hoj-dev Compose project.'
            if (-not $NoBrowser) { Start-Process $url }
        }
    }
    $resultCode = 0
} catch {
    Write-Host ('FAILED: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host ('Log: ' + $logFile)
    $resultCode = 1
} finally { Stop-Transcript | Out-Null }
exit $resultCode
