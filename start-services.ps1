$ErrorActionPreference = 'Stop'

$modules = [ordered]@{
    'account-service'    = @{ Variable = 'HOTEL_ACCOUNT_PORT'; Port = 13308 }
    'catalog-service'    = @{ Variable = 'HOTEL_CATALOG_PORT'; Port = 13309 }
    'order-service'      = @{ Variable = 'HOTEL_ORDER_PORT'; Port = 13310 }
    'engagement-service' = @{ Variable = 'HOTEL_ENGAGEMENT_PORT'; Port = 13311 }
    'pet-service'        = @{ Variable = 'HOTEL_PET_PORT'; Port = 13312 }
    'gateway'            = @{ Variable = 'HOTEL_GATEWAY_PORT'; Port = 13307 }
}
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    throw 'Set JWT_SECRET before starting the services.'
}
try {
    $jwtBytes = [Convert]::FromBase64String($env:JWT_SECRET)
} catch {
    throw 'JWT_SECRET must be Base64 encoded.'
}
if ($jwtBytes.Length -lt 32) {
    throw 'JWT_SECRET must contain at least 32 random bytes.'
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw "Java was not found at $java." }
$version = ([xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml') -Raw)).project.version
$listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
$ports = @{}
foreach ($module in $modules.Keys) {
    $setting = $modules[$module]
    $override = [Environment]::GetEnvironmentVariable($setting.Variable)
    $port = $setting.Port
    if ($override -and ![int]::TryParse($override, [ref]$port)) {
        throw "Invalid port in $($setting.Variable)."
    }
    if ($port -lt 1 -or $port -gt 65535 -or $port -in $ports.Values) {
        throw "Invalid or duplicate port $port for $module."
    }
    if ($port -in $listeners.Port) { throw "Port $port for $module is already in use." }
    $ports[$module] = $port

    $jar = Join-Path $PSScriptRoot "$module/target/$module-$version.jar"
    if (!(Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Missing $jar. Run mvn package first."
    }
}

& (Join-Path $PSScriptRoot 'publish-sentinel-rules.ps1')

$registry = if ($env:NACOS_SERVER_ADDR) { $env:NACOS_SERVER_ADDR } else { '127.0.0.1:8848' }
$namespace = if ($env:NACOS_NAMESPACE) { $env:NACOS_NAMESPACE } else { '' }
$serviceGroup = if ($env:NACOS_GROUP) { $env:NACOS_GROUP } else { 'DEFAULT_GROUP' }
$seataGroup = if ($env:SEATA_NACOS_GROUP) { $env:SEATA_NACOS_GROUP } else { 'SEATA_GROUP' }
$login = Invoke-RestMethod -Method Post -Uri "http://$registry/nacos/v1/auth/login" -Body @{
    username = $(if ($env:NACOS_USERNAME) { $env:NACOS_USERNAME } else { 'nacos' })
    password = $(if ($env:NACOS_PASSWORD) { $env:NACOS_PASSWORD } else { 'nacos' })
}
function Get-RegisteredInstances($name, $group) {
    $parameters = @{ serviceName = $name; groupName = $group; namespaceId = $namespace; healthyOnly = 'true'; accessToken = $login.accessToken }
    $query = ($parameters.GetEnumerator() | ForEach-Object {
        [Uri]::EscapeDataString($_.Key) + '=' + [Uri]::EscapeDataString([string]$_.Value)
    }) -join '&'
    $result = Invoke-RestMethod -Uri "http://$registry/nacos/v1/ns/instance/list?$query" -TimeoutSec 3
    @($result.hosts | Where-Object { $_.healthy -and $_.enabled })
}
$coordinators = @(Get-RegisteredInstances 'seata-server' $seataGroup)
if (!$coordinators.Count) { throw 'Seata is not registered. Run docker compose -f seata/compose.yml up -d and wait for it to start.' }
$coordinator = $coordinators[0]
$connection = [Net.Sockets.TcpClient]::new()
try {
    $pending = $connection.ConnectAsync([string]$coordinator.ip, [int]$coordinator.port)
    if (!$pending.Wait(3000) -or !$connection.Connected) { throw 'Seata RPC port is unavailable.' }
} finally { $connection.Dispose() }

$logs = Join-Path $PSScriptRoot 'target/service-logs'
New-Item -ItemType Directory -Path $logs -Force | Out-Null
$processes = @{}
try {
    foreach ($module in $modules.Keys) {
        $processes[$module] = Start-Process -FilePath $java -WindowStyle Hidden -PassThru `
            -ArgumentList @('-jar', "$module-$version.jar") `
            -WorkingDirectory (Join-Path $PSScriptRoot "$module/target") `
            -RedirectStandardOutput (Join-Path $logs "$module.log") `
            -RedirectStandardError (Join-Path $logs "$module.err.log")
        Write-Host "$module starting on port $($ports[$module])"
        if ($module -eq 'catalog-service') {
            $deadline = [DateTime]::UtcNow.AddSeconds(60)
            while (!(Get-RegisteredInstances 'catalog-service' $serviceGroup | Where-Object { $_.port -eq $ports[$module] })) {
                if ($processes[$module].HasExited) { throw "Catalog exited. Check $logs." }
                if ([DateTime]::UtcNow -gt $deadline) { throw "Catalog startup timed out. Check $logs." }
                Start-Sleep -Seconds 1
            }
        }
    }
    Write-Host "Gateway http://localhost:$($ports.gateway)/"
    Write-Host "Logs are in $logs. Press Ctrl+C to stop the services."
    while ($true) {
        foreach ($module in $processes.Keys) {
            if ($processes[$module].HasExited) {
                throw "$module exited with code $($processes[$module].ExitCode). Check $logs."
            }
        }
        Start-Sleep -Seconds 1
    }
} finally {
    foreach ($process in $processes.Values) {
        if (!$process.HasExited) { Stop-Process -InputObject $process -ErrorAction SilentlyContinue }
    }
}
