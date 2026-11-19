param([switch]$Replace)

$ErrorActionPreference = 'Stop'
$registry = if ($env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR } else { '127.0.0.1:8848' }
$baseUrl = "http://$registry/nacos/v1"
$group = if ($env:SENTINEL_NACOS_GROUP) { $env:SENTINEL_NACOS_GROUP } else { 'SENTINEL_GROUP' }
$namespace = if ($env:NACOS_NAMESPACE) { $env:NACOS_NAMESPACE } else { '' }
$username = if ($env:NACOS_USERNAME) { $env:NACOS_USERNAME } else { 'nacos' }
$password = if ($env:NACOS_PASSWORD) { $env:NACOS_PASSWORD } else { 'nacos' }
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/login" -Body @{ username = $username; password = $password }

foreach ($file in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'sentinel') -Filter '*-rules.json') {
    $content = [IO.File]::ReadAllText($file.FullName)
    $null = ConvertFrom-Json $content
    $parameters = @{
        dataId = $file.Name
        group = $group
        tenant = $namespace
        accessToken = $login.accessToken
    }
    $query = ($parameters.GetEnumerator() | ForEach-Object {
        [Uri]::EscapeDataString($_.Key) + '=' + [Uri]::EscapeDataString([string]$_.Value)
    }) -join '&'
    $exists = $false
    try {
        $null = Invoke-WebRequest -Uri "$baseUrl/cs/configs?$query"
        $exists = $true
    } catch {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
    }
    if ($exists -and !$Replace) {
        Write-Host "Kept existing Nacos rules $($file.Name) in $group"
        continue
    }
    $parameters.content = $content
    $parameters.type = 'json'
    $result = Invoke-RestMethod -Method Post -Uri "$baseUrl/cs/configs" -Body $parameters
    if ([string]$result -ne 'true') { throw "Nacos rejected $($file.Name)." }
    Write-Host "Published $($file.Name) to $group"
}
