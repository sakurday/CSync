#Requires -RunAsAdministrator
param([int]$Port = 45876, [switch]$PublicAccess)
$ErrorActionPreference = 'Stop'
if ($Port -lt 1 -or $Port -gt 65535) { throw 'Port must be 1..65535' }
$executable = Join-Path (Split-Path -Parent $PSScriptRoot) 'dist\CSync.exe'
if (!(Test-Path -LiteralPath $executable)) { throw 'Build dist/CSync.exe first' }
$ruleName = "CSync TCP $Port"
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) { throw "Rule already exists: $ruleName. Review or remove it explicitly before replacing it." }
$profile = if ($PublicAccess) { 'Any' } else { 'Private' }
$remote = if ($PublicAccess) { 'Any' } else { 'LocalSubnet' }
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Program $executable -Protocol TCP -LocalPort $Port -Profile $profile -RemoteAddress $remote
