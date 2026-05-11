param(
    [string] $Repo = "Nulifyer/ShareToLens",
    [string] $Alias = "sharetolens",
    [string] $KeystorePath = ".release/sharetolens-release.jks",
    [int] $ValidityDays = 10000,
    [switch] $Upload,
    [switch] $Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function ConvertFrom-SecureStringToPlainText {
    param([securestring] $SecureString)

    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        if ($bstr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        }
    }
}

function Set-GitHubSecret {
    param(
        [string] $Name,
        [string] $Value
    )

    $Value | gh secret set $Name --repo $Repo
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    throw "keytool not found. Install a JDK and ensure keytool is on PATH."
}

$resolvedKeystorePath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($KeystorePath)
$keystoreDir = Split-Path -Parent $resolvedKeystorePath
New-Item -ItemType Directory -Path $keystoreDir -Force | Out-Null

if ((Test-Path -LiteralPath $resolvedKeystorePath) -and -not $Force) {
    Write-Host "Using existing keystore: $resolvedKeystorePath"
} else {
    if ((Test-Path -LiteralPath $resolvedKeystorePath) -and $Force) {
        Remove-Item -LiteralPath $resolvedKeystorePath
    }

    $storePasswordSecure = Read-Host "Release keystore password" -AsSecureString
    $keyPasswordSecure = Read-Host "Release key password" -AsSecureString
    $storePassword = ConvertFrom-SecureStringToPlainText $storePasswordSecure
    $keyPassword = ConvertFrom-SecureStringToPlainText $keyPasswordSecure

    try {
        $env:SHARETOLENS_STORE_PASSWORD = $storePassword
        $env:SHARETOLENS_KEY_PASSWORD = $keyPassword

        & $keytool.Source -genkeypair `
            -v `
            -keystore $resolvedKeystorePath `
            -storetype JKS `
            -keyalg RSA `
            -keysize 4096 `
            -validity $ValidityDays `
            -alias $Alias `
            -dname "CN=Share to Lens, OU=Release, O=Nulifyer, L=Unknown, ST=Unknown, C=US" `
            -storepass:env SHARETOLENS_STORE_PASSWORD `
            -keypass:env SHARETOLENS_KEY_PASSWORD
    } finally {
        Remove-Item Env:\SHARETOLENS_STORE_PASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:\SHARETOLENS_KEY_PASSWORD -ErrorAction SilentlyContinue
    }
}

$base64Path = "$resolvedKeystorePath.base64"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($resolvedKeystorePath)) |
    Set-Content -NoNewline -LiteralPath $base64Path

Write-Host "Wrote keystore: $resolvedKeystorePath"
Write-Host "Wrote base64:   $base64Path"

if ($Upload) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $gh) {
        throw "GitHub CLI not found. Install gh or rerun without -Upload and add secrets manually."
    }

    gh auth status --hostname github.com | Out-Null

    if (-not (Get-Variable -Name storePassword -Scope Local -ErrorAction SilentlyContinue)) {
        $storePassword = ConvertFrom-SecureStringToPlainText (Read-Host "Existing keystore password" -AsSecureString)
    }
    if (-not (Get-Variable -Name keyPassword -Scope Local -ErrorAction SilentlyContinue)) {
        $keyPassword = ConvertFrom-SecureStringToPlainText (Read-Host "Existing key password" -AsSecureString)
    }

    Set-GitHubSecret "SIGNING_KEYSTORE_BASE64" (Get-Content -Raw -LiteralPath $base64Path)
    Set-GitHubSecret "SIGNING_KEY_ALIAS" $Alias
    Set-GitHubSecret "SIGNING_KEY_PASSWORD" $keyPassword
    Set-GitHubSecret "SIGNING_STORE_PASSWORD" $storePassword

    Write-Host "Uploaded signing secrets to $Repo"
} else {
    Write-Host ""
    Write-Host "Add these GitHub Actions secrets:"
    Write-Host "  SIGNING_KEYSTORE_BASE64 = contents of $base64Path"
    Write-Host "  SIGNING_KEY_ALIAS = $Alias"
    Write-Host "  SIGNING_KEY_PASSWORD = release key password"
    Write-Host "  SIGNING_STORE_PASSWORD = release keystore password"
    Write-Host ""
    Write-Host "Or upload them automatically:"
    Write-Host "  .\scripts\setup-release-secrets.ps1 -Upload"
}
