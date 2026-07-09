$logFile = 'C:\Users\michael\.catpaw\projects\ided--qclaw-workspace-visionlink-android\670c78f2-8c7d-4431-9b79-f85c2aa507b0\terminals\e1b815ac-58e7-4997-8b95-d23959ef2532-shell-55.log'
while ($true) {
    $content = Get-Content $logFile -Tail 10 -ErrorAction SilentlyContinue
    if ($content -match 'BUILD SUCCESSFUL|BUILD FAILED|FAILURE') {
        Write-Output ($content -join "`n")
        break
    }
    Start-Sleep -Seconds 5
}
