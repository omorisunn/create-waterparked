$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$base = (Get-Location).Path
$src  = Join-Path $base 'run\shaderpacks\iterationRP Alpha 0.8.26 hotfix.zip'
$dst  = Join-Path $base 'run\shaderpacks\iterationRP Alpha 0.8.26 hotfix + Waterparked.zip'
$targ = 'shaders/Lib/Programs/Composite/Translucent_FS.glsl'

# --- 1) read the original Translucent_FS ---
$srcZ = [System.IO.Compression.ZipFile]::OpenRead($src)
$e0 = $srcZ.Entries | Where-Object FullName -ceq $targ
if (-not $e0) { Write-Output "target entry not found in source"; $srcZ.Dispose(); exit 1 }
$r = New-Object System.IO.StreamReader($e0.Open(), [System.Text.Encoding]::UTF8)
$orig = $r.ReadToEnd(); $r.Close()

# --- 2) patch: pure-water depth fallback -> thin 2.5-block layer for OUR water
#        (blue-dominant albedo, tint 0.3/0.6/1, b/r ~3.3). Vanilla water albedo
#        is pale (b/r ~1.4) and keeps the original depth behavior. ---
$elseP = [regex]'(\t+)\}else\{\r?\n(\t+)waterDeep = opaqueDist - waterDist;\r?\n\1\}'
if (-not $elseP.IsMatch($orig)) { Write-Output 'PATCH FAILED: pure-water else not found'; $srcZ.Dispose(); exit 1 }
$patched = $elseP.Replace($orig, { param($m)
    $ind  = $m.Groups[1].Value
    $ind2 = $m.Groups[2].Value
    $ind + '}else{' + "`r`n" + $ind2 + "// Waterparked: our colorwheel water (tube band / thrown stream) carries a" + "`r`n" + $ind2 + "// blue-dominant albedo (tint 0.3/0.6/1, b/r ~3.3); treat it as a thin" + "`r`n" + $ind2 + "// layer so it never fogs up like deep ocean. Vanilla water's albedo is" + "`r`n" + $ind2 + "// pale (b/r ~1.4) and keeps the pack's original depth behavior." + "`r`n" + $ind2 + "waterDeep = (gbuffer.albedo.b > gbuffer.albedo.r * 1.6)" + "`r`n" + $ind2 + "`t? min(opaqueDist - waterDist, 2.5)" + "`r`n" + $ind2 + "`t: opaqueDist - waterDist;" + "`r`n" + $ind + '}'
})
$srcZ.Dispose()

# --- 3) verify ---
if ($patched -eq $orig) { Write-Output 'PATCH FAILED: output == input'; exit 1 }
if ($patched -notmatch 'gbuffer\.albedo\.b > gbuffer\.albedo\.r \* 1\.6') { Write-Output 'PATCH FAILED: marker missing'; exit 1 }
if ($patched -notmatch 'min\(opaqueDist - waterDist, 2\.5\)') { Write-Output 'PATCH FAILED: clamp missing'; exit 1 }
Write-Output "patch verified ($($patched.Length - $orig.Length) bytes added)"
$bytes = [System.Text.Encoding]::UTF8.GetBytes($patched)

# --- 4) build patched zip: copy all entries, replace the patched file ---
if (Test-Path $dst) { Remove-Item $dst -Force }
$srcZ2 = [System.IO.Compression.ZipFile]::OpenRead($src)
$dstFs = [System.IO.File]::Create($dst)
$dstZ = New-Object System.IO.Compression.ZipArchive($dstFs, [System.IO.Compression.ZipArchiveMode]::Create)
$srcZ2.Entries | ForEach-Object {
    $e = $_
    if ($e.FullName -eq $targ) {
        $ne = $dstZ.CreateEntry($e.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        $os = $ne.Open(); $os.Write($bytes, 0, $bytes.Length); $os.Close()
        Write-Output ("  replaced: {0}" -f $e.FullName)
    } else {
        $ne = $dstZ.CreateEntry($e.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
        $is = $e.Open(); $os = $ne.Open(); $is.CopyTo($os); $os.Close(); $is.Close()
    }
}
$dstZ.Dispose(); $srcZ2.Dispose(); $dstFs.Close()
Write-Output ("DONE -> {0} ({1:N0} bytes)" -f $dst, (Get-Item $dst).Length)
