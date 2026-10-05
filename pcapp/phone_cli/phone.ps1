# phone — the phone's files from the PC, through PC Remote (no Python needed).
# Installed by PC Remote into %LOCALAPPDATA%\pc-remote\bin; `phone --help` for usage.
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Data = if ($env:PC_REMOTE_DATA) { $env:PC_REMOTE_DATA } else { Join-Path $env:LOCALAPPDATA "pc-remote" }
$Base = if ($env:PC_REMOTE_URL) { $env:PC_REMOTE_URL } else { "http://127.0.0.1:8787" }
$Mirror = Join-Path $Data "phone"

function Get-Secret {
    $cfg = Join-Path $Data "config.json"
    if (-not (Test-Path $cfg)) { Write-Error "phone: нет $cfg — запущен ли PC Remote?"; exit 1 }
    return (Get-Content $cfg -Raw -Encoding UTF8 | ConvertFrom-Json).secret
}
$Headers = @{ Authorization = "Bearer " + (Get-Secret) }

function Fail($msg) { [Console]::Error.WriteLine("phone: $msg"); exit 1 }
function Call($op, $extra) {
    $body = @{ op = $op }
    if ($extra) { foreach ($k in $extra.Keys) { $body[$k] = $extra[$k] } }
    try {
        return Invoke-RestMethod -Method Post -Uri "$Base/api/phone" -Headers $Headers -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))) -TimeoutSec 90
    } catch {
        $r = $_.Exception.Response
        if ($r) { try { $sr = New-Object IO.StreamReader($r.GetResponseStream()); $j = $sr.ReadToEnd() | ConvertFrom-Json; Fail $j.error } catch { } }
        Fail "PC Remote не отвечает ($($_.Exception.Message))"
    }
}
function Size($n) {
    if ($n -lt 1024) { return "$n Б" }; $n = $n / 1024
    if ($n -lt 1024) { return ("{0:N1} КБ" -f $n) }; $n = $n / 1024
    if ($n -lt 1024) { return ("{0:N1} МБ" -f $n) }; return ("{0:N1} ГБ" -f ($n / 1024))
}
function Stamp($t) { if (-not $t) { return "" }; return ([DateTimeOffset]::FromUnixTimeSeconds([int64]$t).LocalDateTime.ToString("yyyy-MM-dd HH:mm")) }
function Show-Items($items) {
    foreach ($it in $items) {
        if ($it.dir) { "{0,10}  {1}  {2}/" -f "<папка>", (Stamp $it.mtime), $it.name }
        else { "{0,10}  {1}  {2}" -f (Size $it.size), (Stamp $it.mtime), $it.name }
    }
}
function Get-File($remote, $local, $quiet) {
    $url = "$Base/api/phone?op=read&path=" + [Uri]::EscapeDataString($remote)
    $dir = Split-Path -Parent $local; if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    try { $resp = Invoke-WebRequest -Uri $url -Headers $Headers -OutFile $local -PassThru -TimeoutSec 600 }
    catch { $r = $_.Exception.Response; if ($r) { try { $sr = New-Object IO.StreamReader($r.GetResponseStream()); $j = $sr.ReadToEnd() | ConvertFrom-Json; Fail $j.error } catch { } }; Fail $_.Exception.Message }
    $item = $resp.Headers["X-Item"]
    if ($item) { try { $m = ($item | ConvertFrom-Json).mtime; if ($m) { (Get-Item $local).LastWriteTime = [DateTimeOffset]::FromUnixTimeSeconds([int64]$m).LocalDateTime } } catch { } }
    if (-not $quiet) { "$remote -> $local ($(Size (Get-Item $local).Length))" }
}
function Put-File($local, $remote, $quiet) {
    if (-not (Test-Path $local -PathType Leaf)) { Fail "нет файла $local" }
    if ($remote.EndsWith("/")) { $remote += (Split-Path -Leaf $local) }
    $url = "$Base/api/phone?op=write&path=" + [Uri]::EscapeDataString($remote)
    try { Invoke-RestMethod -Method Post -Uri $url -Headers $Headers -ContentType "application/octet-stream" -InFile $local -TimeoutSec 600 | Out-Null }
    catch { $r = $_.Exception.Response; if ($r) { try { $sr = New-Object IO.StreamReader($r.GetResponseStream()); $j = $sr.ReadToEnd() | ConvertFrom-Json; Fail $j.error } catch { } }; Fail $_.Exception.Message }
    if (-not $quiet) { "$local -> $remote ($(Size (Get-Item $local).Length))" }
}
function Mirror-Path($remote) { return Join-Path $Mirror (($remote -replace '^/sdcard', 'sdcard').Trim('/') -replace '/', '\') }
function Pull($remote, $local, $level) {
    $res = Call "list" @{ path = $remote }
    if (-not $local) { $local = Mirror-Path $res.path }
    if (-not (Test-Path $local)) { New-Item -ItemType Directory -Force $local | Out-Null }
    $n = 0
    foreach ($it in $res.items) {
        $dest = Join-Path $local $it.name
        if ($it.dir) { $n += (Pull $it.path $dest ($level + 1)); continue }
        if ((Test-Path $dest) -and ((Get-Item $dest).Length -eq $it.size) -and ([Math]::Abs(((Get-Item $dest).LastWriteTime - [DateTimeOffset]::FromUnixTimeSeconds([int64]$it.mtime).LocalDateTime).TotalSeconds) -lt 2)) { continue }
        Get-File $it.path $dest $true; $n++; "  $($it.path)"
    }
    if ($level -eq 0) { "готово: $local (новых/изменённых файлов: $n)" }
    return $n
}
function Tree($path, $depth, $prefix, $level) {
    $items = (Call "list" @{ path = $path }).items
    for ($i = 0; $i -lt $items.Count; $i++) {
        $it = $items[$i]; $last = ($i -eq $items.Count - 1)
        $tail = if ($it.dir) { "/" } else { "  (" + (Size $it.size) + ")" }
        "$prefix$(if ($last) { '└── ' } else { '├── ' })$($it.name)$tail"
        if ($it.dir -and ($level + 1) -lt $depth) { Tree $it.path $depth ($prefix + $(if ($last) { '    ' } else { '│   ' })) ($level + 1) }
    }
}

$cmd = if ($args.Count) { $args[0] } else { "help" }
$a = @($args | Select-Object -Skip 1)
switch ($cmd) {
    { $_ -in "help", "-h", "--help" } {
        @"
phone — файлы телефона с ПК (через PC Remote)

  phone roots                           папки, которые стоит знать (камера, скриншоты, загрузки…)
  phone ls [/sdcard/DCIM]               содержимое папки
  phone tree [/sdcard/DCIM] [-d 3]      дерево папок
  phone find /sdcard "*.jpg"            поиск по имени (маска, без учёта регистра)
  phone stat /sdcard/x.jpg              размер и дата
  phone cat /sdcard/notes.txt           вывести текстовый файл
  phone get /sdcard/x.jpg [локально]    скачать файл
  phone put файл.txt /sdcard/Download/  отправить файл
  phone pull /sdcard/DCIM/Camera [папка]  зеркало папки на ПК (по умолчанию $Mirror\...)
  phone push папка /sdcard/Download/x   отправить папку
  phone rm | mkdir | mv                 удалить, создать папку, переименовать
"@
    }
    "roots" { $r = Call "roots"; foreach ($it in $r.items) { "{0,-16} {1}" -f $it.name, $it.path }; if ($r.total) { "свободно $(Size $r.free) из $(Size $r.total)" } }
    "ls" { $r = Call "list" @{ path = $(if ($a.Count) { $a[0] } else { "/sdcard" }) }; $r.path; Show-Items $r.items }
    "tree" {
        $depth = 2; $rest = [System.Collections.ArrayList]@($a)
        $i = $rest.IndexOf("-d"); if ($i -ge 0) { $depth = [int]$rest[$i + 1]; $rest.RemoveRange($i, 2) }
        $p = if ($rest.Count) { $rest[0] } else { "/sdcard" }; $p; Tree $p $depth "" 0
    }
    "find" { if ($a.Count -lt 2) { Fail "phone find <папка> <маска>" }; $r = Call "find" @{ path = $a[0]; q = $a[1] }
             foreach ($it in $r.items) { "{0,10}  {1}  {2}" -f (Size $it.size), (Stamp $it.mtime), $it.path }; if (-not $r.items) { "ничего не найдено" } }
    "stat" { (Call "stat" @{ path = $a[0] }).item | ConvertTo-Json }
    "cat" { $tmp = Join-Path $Mirror ".cat.tmp"; Get-File $a[0] $tmp $true; Get-Content $tmp -Raw -Encoding UTF8; Remove-Item $tmp -Force }
    "get" { $remote = $a[0]; $local = if ($a.Count -gt 1) { $a[1] } else { Split-Path -Leaf $remote }
            if (Test-Path $local -PathType Container) { $local = Join-Path $local (Split-Path -Leaf $remote) }; Get-File $remote $local $false }
    "put" { if ($a.Count -lt 2) { Fail "phone put <файл> <путь на телефоне>" }; Put-File $a[0] $a[1] $false }
    "pull" { Pull $(if ($a.Count) { $a[0] } else { "/sdcard/DCIM/Camera" }) $(if ($a.Count -gt 1) { $a[1] } else { $null }) 0 | Out-Null }
    "push" { if ($a.Count -lt 2) { Fail "phone push <папка> <папка на телефоне>" }; $n = 0
             Get-ChildItem $a[0] -Recurse -File | ForEach-Object { $rel = $_.FullName.Substring((Resolve-Path $a[0]).Path.Length).TrimStart('\') -replace '\\', '/'
               Put-File $_.FullName ($a[1].TrimEnd('/') + "/" + $rel) $true; $n++; "  $rel" }; "готово: $n файлов -> $($a[1])" }
    "rm" { if ((Call "delete" @{ path = $a[0] }).ok) { "удалено" } else { "не удалось" } }
    "mkdir" { if ((Call "mkdir" @{ path = $a[0] }).ok) { "ok" } else { "не удалось" } }
    "mv" { if ((Call "move" @{ path = $a[0]; to = $a[1] }).ok) { "ok" } else { "не удалось" } }
    default { Fail "неизвестная команда $cmd (phone --help)" }
}
