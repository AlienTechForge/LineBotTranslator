param(
    [Parameter(Mandatory = $true)]
    [string]$ImagePath
)

$resolvedImage = (Resolve-Path -LiteralPath $ImagePath -ErrorAction Stop).Path
& "$PSScriptRoot\..\mvnw.cmd" `
    "-Dtest=ImageTranslationReplayCorpusTests#maintainerCanReplaySanitizedGeometryAgainstAnExternalPrivateImage" `
    "-Dimage.translation.replay.image=$resolvedImage" test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Replay artifact: target\image-translation-replay\external-private-regions.png"
