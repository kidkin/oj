[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9.-]*(?::[0-9]+)?/[a-z0-9][a-z0-9_./-]*$')]
    [string]$RegistryNamespace,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$')]
    [string]$Version,
    [switch]$PlanOnly,
    [string]$CranePath
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$RegistryNamespace = $RegistryNamespace.TrimEnd('/')
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $dockerExe = $dockerCommand.Source
} else {
    $dockerExe = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
}
if (-not (Test-Path -LiteralPath $dockerExe)) { throw 'Docker CLI was not found. Start Docker Desktop first.' }
$dockerBin = Split-Path -Parent $dockerExe
$env:PATH = $dockerBin + ';' + $env:PATH
if ($CranePath) {
    $CranePath = (Resolve-Path -LiteralPath $CranePath).Path
    if (-not (Test-Path -LiteralPath $CranePath -PathType Leaf)) { throw 'crane executable was not found.' }
    # This affects this publisher process only, not Docker Desktop settings.
    $registryHost = $RegistryNamespace.Split('/')[0]
    $directHosts = $registryHost + ',.aliyuncs.com'
    $env:NO_PROXY = $(if ($env:NO_PROXY) { $env:NO_PROXY + ',' + $directHosts } else { $directHosts })
}
$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if (-not $gitCommand) { throw 'Git was not found. Open a new terminal after installing Git.' }
$gitExe = $gitCommand.Source

function Invoke-GitRead {
    param([string[]]$GitArguments)
    $result = & $gitExe -C $repoRoot @GitArguments
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify application source in Git.' }
    return (($result | Out-String).Trim())
}

$applications = @(
    @{ Local = 'hoj-local/backend:dev'; Remote = 'hoj-backend'; Source = 'hoj-springboot' },
    @{ Local = 'hoj-local/frontend:dev'; Remote = 'hoj-frontend'; Source = 'hoj-vue' },
    @{ Local = 'hoj-local/judgeserver:dev'; Remote = 'hoj-judgeserver'; Source = 'hoj-springboot' }
)
$releaseImages = @()
foreach ($app in $applications) {
    $dirty = Invoke-GitRead -GitArguments @('status', '--porcelain', '--untracked-files=normal', '--', $app.Source)
    if ($dirty) { throw ('Commit application changes before publishing: ' + $app.Source) }
    $inspection = & $dockerExe image inspect $app.Local
    if ($LASTEXITCODE -ne 0) { throw ('Build the local application image first: ' + $app.Local) }
    $image = @((($inspection | Out-String) | ConvertFrom-Json))[0]
    if ($image.Os -ne 'linux' -or $image.Architecture -ne 'amd64') {
        throw ('The current ECS installer requires a linux/amd64 application image: ' + $app.Local)
    }
    $revision = $image.Config.Labels.'org.opencontainers.image.revision'
    if ($image.Config.Labels.'hoj.build' -ne 'source' -or $revision -notmatch '^[0-9a-f]{7,40}$') {
        throw ('The local image does not identify a committed source build: ' + $app.Local)
    }
    $imageTree = Invoke-GitRead -GitArguments @('rev-parse', ($revision + ':' + $app.Source))
    $currentTree = Invoke-GitRead -GitArguments @('rev-parse', ('HEAD:' + $app.Source))
    if ($imageTree -ne $currentTree) { throw ('Application code changed after this image was built. Rebuild: ' + $app.Local) }
    $releaseImages += [pscustomobject]@{
        LocalImage = $app.Local
        LocalImageId = $image.Id
        SourceRevision = $revision
        ApplicationTree = $imageTree
        RootFsDiffIds = @($image.RootFS.Layers)
        PublishedImage = ($RegistryNamespace + '/' + $app.Remote + ':' + $Version)
    }
}

$logRoot = Join-Path $repoRoot 'logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$reportName = if ($PlanOnly) { 'release-plan-' + $Version + '.json' } else { 'release-images-' + $Version + '.json' }
$reportPath = Join-Path $logRoot $reportName
if (-not $PlanOnly -and (Test-Path -LiteralPath $reportPath)) { throw 'A release record already exists for this version. Review it and use a new version for changes.' }
$publicReleaseRoot = Join-Path $repoRoot 'releases'
$publicReleasePath = Join-Path $publicReleaseRoot ($Version + '.json')
if (-not $PlanOnly -and (Test-Path -LiteralPath $publicReleasePath)) { throw 'A public release manifest already exists for this version. Use a new version.' }

if (-not $PlanOnly) {
    foreach ($entry in $releaseImages) {
        & $dockerExe tag $entry.LocalImageId $entry.PublishedImage
        if ($LASTEXITCODE -ne 0) { throw ('Image tagging failed: ' + $entry.LocalImage) }
        if ($CranePath) {
            $archiveRoot = Join-Path $logRoot 'image-archives'
            New-Item -ItemType Directory -Force -Path $archiveRoot | Out-Null
            $archiveName = $entry.LocalImage.Replace('/', '-').Replace(':', '-') + '-' + $Version + '.tar'
            $archivePath = Join-Path $archiveRoot $archiveName
            Write-Output ('Exporting and uploading: ' + $entry.PublishedImage)
            & $dockerExe image save --platform linux/amd64 --output $archivePath $entry.PublishedImage
            if ($LASTEXITCODE -ne 0) { throw ('Image export failed: ' + $entry.LocalImage) }
            & $CranePath push --platform linux/amd64 $archivePath $entry.PublishedImage
            if ($LASTEXITCODE -ne 0) { throw ('Direct registry upload failed: ' + $entry.PublishedImage) }
            $remoteConfigText = & $CranePath config --platform linux/amd64 $entry.PublishedImage
            if ($LASTEXITCODE -ne 0) { throw ('Could not verify published image: ' + $entry.PublishedImage) }
            $remoteConfig = (($remoteConfigText | Out-String) | ConvertFrom-Json)
            if ($remoteConfig.os -ne 'linux' -or $remoteConfig.architecture -ne 'amd64' -or
                $remoteConfig.config.Labels.'org.opencontainers.image.revision' -ne $entry.SourceRevision -or
                ($remoteConfig.rootfs.diff_ids -join ';') -cne ($entry.RootFsDiffIds -join ';')) {
                throw ('Published image does not match the validated local image: ' + $entry.PublishedImage)
            }
            $remoteDigest = & $CranePath digest $entry.PublishedImage
            if ($LASTEXITCODE -ne 0 -or $remoteDigest -notmatch '^sha256:[0-9a-f]{64}$') { throw 'Could not verify registry image digest.' }
            $entry | Add-Member -NotePropertyName RemoteDigest -NotePropertyValue $remoteDigest
            Remove-Item -LiteralPath $archivePath
            Write-Output ('Verified published image: ' + $entry.PublishedImage)
        } else {
            $pushedDigest = & $dockerExe push --quiet --platform linux/amd64 $entry.PublishedImage
            if ($LASTEXITCODE -ne 0) { throw ('Image push failed. Check registry login and network access, then retry: ' + $entry.PublishedImage) }
            $pushedDigest = ($pushedDigest | Out-String).Trim()
            if ($pushedDigest -notmatch '^sha256:[0-9a-f]{64}$') { throw 'Docker push did not return a valid published digest.' }
            $entry | Add-Member -NotePropertyName RemoteDigest -NotePropertyValue $pushedDigest
        }
    }
}
$report = [pscustomobject]@{
    Version = $Version
    Status = $(if ($PlanOnly) { 'planned' } else { 'published' })
    Transport = $(if ($CranePath) { 'crane' } else { 'docker' })
    Images = $releaseImages
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
if (-not $PlanOnly) {
    # This manifest is safe to commit: image identities and schema hashes only.
    $publicImages = [ordered]@{}
    foreach ($entry in $releaseImages) {
        $service = $entry.PublishedImage.Split('/')[-1].Split(':')[0]
        $publicImages[$service] = [ordered]@{
            Reference = $entry.PublishedImage; Digest = $entry.RemoteDigest
            SourceRevision = $entry.SourceRevision; RootFsDiffIds = @($entry.RootFsDiffIds)
        }
    }
    $migrationHashes = [ordered]@{}
    $migrationRoot = Join-Path $repoRoot 'hoj-springboot\DataBackup\src\main\resources\db\edu'
    if (Test-Path -LiteralPath $migrationRoot) {
        foreach ($migration in (Get-ChildItem -LiteralPath $migrationRoot -Filter 'V*.sql' | Sort-Object Name)) {
            $migrationHashes[$migration.BaseName] = (Get-FileHash -LiteralPath $migration.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $publicManifest = [ordered]@{ Version = $Version; ReleasedOn = (Get-Date -Format 'yyyy-MM-dd'); Images = $publicImages; Migrations = $migrationHashes }
    New-Item -ItemType Directory -Force -Path $publicReleaseRoot | Out-Null
    $manifestText = ($publicManifest | ConvertTo-Json -Depth 7).Replace("`r`n", "`n") + "`n"
    [IO.File]::WriteAllText($publicReleasePath, $manifestText, (New-Object Text.UTF8Encoding($false)))
    Write-Output ('Commit this release manifest with the matching Git tag: ' + $publicReleasePath)
}
$releaseImages | Format-Table PublishedImage, SourceRevision -AutoSize
Write-Output ('Release record: ' + $reportPath)
if ($PlanOnly) { Write-Output 'Plan only: no images were tagged or pushed.' }
