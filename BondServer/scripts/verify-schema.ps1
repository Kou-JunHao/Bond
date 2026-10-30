#requires -Version 5.1
<#
.SYNOPSIS
    Compare MyBatis-Plus entity fields with Flyway migrations (CREATE TABLE + later ALTER TABLE ADD COLUMN).
.DESCRIPTION
    Parses every @TableName entity under bond-common and compares its camelCase->snake_case field set
    with the columns declared by db/migration/*.sql:
      * entity field missing in DDL -> FAIL (runtime: column does not exist)
      * DDL column missing in entity -> FAIL (write-only / typo)
    This is the only schema check that can run locally without PostgreSQL.
.EXAMPLE
    powershell -NoProfile -File BondServer/scripts/verify-schema.ps1
#>
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$entityDir    = Join-Path $Root 'bond-common/src/main/java/com/bond/common/entity'
$migrationDir = Join-Path $Root 'bond-common/src/main/resources/db/migration'

if (-not (Test-Path -LiteralPath $entityDir))    { throw "entity dir not found: $entityDir" }
if (-not (Test-Path -LiteralPath $migrationDir)) { throw "migration dir not found: $migrationDir" }

$utf8 = [System.Text.Encoding]::UTF8
$migrationFiles = @(Get-ChildItem -LiteralPath $migrationDir -Filter '*.sql' | Sort-Object Name)
if ($migrationFiles.Count -eq 0) { throw "no Flyway migration found in $migrationDir" }

# ---- 1. parse DDL: table -> column list (CREATE TABLE + ALTER TABLE ADD COLUMN) ----
$tables = @{}
foreach ($mf in $migrationFiles) {
    $sql = [System.IO.File]::ReadAllText($mf.FullName, $utf8)

    # CREATE TABLE
    foreach ($m in [regex]::Matches($sql, '(?is)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-z_][a-z0-9_]*)\s*\((.*?)\)\s*;')) {
        $tableName = $m.Groups[1].Value
        $cols = @()
        foreach ($line in ($m.Groups[2].Value -split "`r?`n")) {
            $l = $line.Trim()
            if ($l -eq '') { continue }
            if ($l -match '^(--|CONSTRAINT\b|PRIMARY\b|UNIQUE\b|FOREIGN\b|CHECK\b)') { continue }
            $cm = [regex]::Match($l, '^([a-z_][a-z0-9_]*)\s+\S+')
            if ($cm.Success) { $cols += $cm.Groups[1].Value }
        }
        if ($tables.ContainsKey($tableName)) {
            $tables[$tableName] = @($tables[$tableName] + $cols)
        } else {
            $tables[$tableName] = @($cols)
        }
    }

    # ALTER TABLE ... ADD COLUMN (statement split by ';' to handle multi-line ALTERs)
    foreach ($stmt in ($sql -split ';')) {
        $am = [regex]::Match($stmt, '(?is)ALTER\s+TABLE\s+([a-z_][a-z0-9_]*)')
        if (-not $am.Success) { continue }
        $tableName = $am.Groups[1].Value
        if (-not $tables.ContainsKey($tableName)) { continue }
        foreach ($cm in [regex]::Matches($stmt, '(?is)ADD\s+COLUMN\s+([a-z_][a-z0-9_]*)')) {
            $column = $cm.Groups[1].Value
            if ($tables[$tableName] -notcontains $column) {
                $tables[$tableName] = @($tables[$tableName]) + $column
            }
        }
    }
}

if ($tables.Count -eq 0) { throw "no CREATE TABLE parsed from $migrationDir" }

# ---- 2. parse entities: @TableName + private fields ----
$failures = 0
$checked  = 0
$checkedTableNames = @()
foreach ($file in (Get-ChildItem -LiteralPath $entityDir -Filter '*.java' | Sort-Object Name)) {
    $text = [System.IO.File]::ReadAllText($file.FullName, $utf8)
    $tn = [regex]::Match($text, '@TableName\("([^"]+)"\)')
    if (-not $tn.Success) {
        Write-Host "[SKIP] $($file.Name) (no @TableName)" -ForegroundColor DarkGray
        continue
    }
    $tableName = $tn.Groups[1].Value
    $fields = [System.Collections.Generic.List[string]]::new()
    foreach ($fm in [regex]::Matches($text, '(?m)^\s*private\s+[\w\.<>\[\]]+\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*;')) {
        $fields.Add($fm.Groups[1].Value)
    }
    $expected = @($fields | ForEach-Object { ($_ -creplace '([A-Z])', '_$1').ToLower() } | Sort-Object -Unique)

    if (-not $tables.ContainsKey($tableName)) {
        Write-Host "[FAIL] $($file.Name): table '$tableName' missing in Flyway migrations" -ForegroundColor Red
        $failures++
        continue
    }

    $actual  = @($tables[$tableName] | Sort-Object -Unique)
    $missing = @($expected | Where-Object { $actual -notcontains $_ })
    $extra   = @($actual   | Where-Object { $expected -notcontains $_ })

    if ($missing.Count -gt 0 -or $extra.Count -gt 0) {
        $failures++
        Write-Host "[FAIL] $tableName" -ForegroundColor Red
        if ($missing.Count -gt 0) { Write-Host "   entity fields missing in DDL : $($missing -join ', ')" -ForegroundColor Red }
        if ($extra.Count   -gt 0) { Write-Host "   DDL columns missing in entity: $($extra -join ', ')" -ForegroundColor Red }
    } else {
        Write-Host ("[ OK ] {0,-24} {1,2} columns" -f $tableName, $actual.Count) -ForegroundColor Green
    }
    $checked++
    $checkedTableNames += $tableName
}

Write-Host ''
Write-Host ("migrations: {0} / entities checked: {1} / DDL tables: {2}" -f $migrationFiles.Count, $checked, $tables.Count)
$tablesWithoutEntity = @($tables.Keys | Where-Object { $checkedTableNames -notcontains $_ } | Sort-Object)
if ($tablesWithoutEntity.Count -gt 0) {
    Write-Host ("[INFO] DDL tables without MyBatis entity (expected for entitlement mappers): " + ($tablesWithoutEntity -join ', ')) -ForegroundColor DarkGray
}
if ($failures -gt 0) {
    Write-Host "SCHEMA CHECK FAILED ($failures issue(s))" -ForegroundColor Red
    exit 1
}
Write-Host 'SCHEMA CHECK PASSED: all entity fields match Flyway migrations' -ForegroundColor Green
exit 0
