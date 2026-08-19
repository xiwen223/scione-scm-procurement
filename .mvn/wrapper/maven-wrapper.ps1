param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MavenArguments
)

$ErrorActionPreference = 'Stop'
$propertiesPath = Join-Path $PSScriptRoot 'maven-wrapper.properties'
if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
    throw "Missing Maven Wrapper configuration: $propertiesPath"
}

$properties = ConvertFrom-StringData -StringData (Get-Content -LiteralPath $propertiesPath -Raw)
$distributionUrl = $properties.distributionUrl
if ([string]::IsNullOrWhiteSpace($distributionUrl)) {
    throw "distributionUrl is not configured in $propertiesPath"
}
if ($env:MVNW_REPOURL) {
    $mavenPath = $distributionUrl -replace '^.*org/apache/maven/', ''
    $distributionUrl = "$($env:MVNW_REPOURL.TrimEnd('/'))/org/apache/maven/$mavenPath"
}
if ($distributionUrl -notmatch '-bin\.zip$') {
    throw 'Maven Wrapper only supports a Maven *-bin.zip distribution URL'
}

$archiveName = Split-Path -Leaf $distributionUrl
$distributionName = [System.IO.Path]::GetFileNameWithoutExtension($archiveName) -replace '-bin$', ''
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $urlBytes = [System.Text.Encoding]::UTF8.GetBytes($distributionUrl)
    $urlHash = ([System.BitConverter]::ToString($sha256.ComputeHash($urlBytes))).Replace('-', '').ToLowerInvariant()
} finally {
    $sha256.Dispose()
}

$mavenUserHome = if ($env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME } else { Join-Path $HOME '.m2' }
$mavenHomeParent = Join-Path $mavenUserHome "wrapper\dists\$distributionName\$urlHash"
$mavenHome = Join-Path $mavenHomeParent $distributionName
$mavenCommand = Join-Path $mavenHome 'bin\mvn.cmd'

if (-not (Test-Path -LiteralPath $mavenCommand -PathType Leaf)) {
    $temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("maven-wrapper-" + [System.Guid]::NewGuid().ToString('N'))
    $archivePath = Join-Path $temporaryDirectory $archiveName
    try {
        New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri $distributionUrl -OutFile $archivePath
        if ($properties.distributionSha256Sum) {
            $actualChecksum = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actualChecksum -ne $properties.distributionSha256Sum.ToLowerInvariant()) {
                throw 'Maven distribution checksum validation failed'
            }
        }
        Expand-Archive -LiteralPath $archivePath -DestinationPath $temporaryDirectory -Force
        $extractedHome = Join-Path $temporaryDirectory $distributionName
        if (-not (Test-Path -LiteralPath (Join-Path $extractedHome 'bin\mvn.cmd') -PathType Leaf)) {
            throw "Downloaded archive does not contain $distributionName\bin\mvn.cmd"
        }
        New-Item -ItemType Directory -Path $mavenHomeParent -Force | Out-Null
        Move-Item -LiteralPath $extractedHome -Destination $mavenHomeParent
    } finally {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}

& $mavenCommand @MavenArguments
exit $LASTEXITCODE
