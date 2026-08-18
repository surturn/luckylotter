# Regenerates presentation.docx and presentation.pdf from presentation.md.
#
# The Markdown is the source of truth — edit that, then re-run this. Without it
# the two generated documents drift away from the Markdown silently, which is
# worse than not having them.
#
# Needs: pandoc (winget install JohnMacFarlane.Pandoc) and Node with Playwright.
# PDF is rendered through headless Chromium rather than a PDF engine, so no
# LaTeX install is required and the screenshots page-break the way CSS says.
#
#   pwsh docs/build-docs.ps1

$ErrorActionPreference = 'Stop'

$docs = $PSScriptRoot
$work = Join-Path ([System.IO.Path]::GetTempPath()) 'lucklotter-docs'
New-Item -ItemType Directory -Force -Path $work | Out-Null

$pandoc = (Get-Command pandoc -ErrorAction SilentlyContinue).Source
if (-not $pandoc) { $pandoc = "$env:LOCALAPPDATA\Pandoc\pandoc.exe" }
if (-not (Test-Path $pandoc)) {
  throw "pandoc not found. Install it with: winget install --id JohnMacFarlane.Pandoc"
}

$title    = 'LuckLotter - AI Retention Layer'
$subtitle = 'Demo walkthrough and design notes - Phase 1 MVP'

# The Markdown keeps its own H1 so it reads correctly on GitHub. Pandoc gets a
# copy with that H1 and its standfirst removed, because both arrive again as
# title metadata — otherwise the title appears three times: title block, table
# of contents entry, and heading.
$src = Get-Content (Join-Path $docs 'presentation.md') -Raw -Encoding UTF8
$src = $src -replace '(?s)\A# [^\r\n]*\r?\n\r?\n\*\*Demo walkthrough and design notes\.\*\*[^\r\n]*\r?\n[^\r\n]*\r?\n\r?\n', ''
$body = Join-Path $work 'presentation.body.md'
Set-Content -Path $body -Value $src -Encoding UTF8

# Playwright is declared in docs/package.json, separately from the application's
# own dependencies — this toolchain is not part of the app build.
if (-not (Test-Path (Join-Path $docs 'node_modules/playwright'))) {
  Write-Output 'Installing the PDF toolchain (first run only)...'
  Push-Location $docs
  npm install --no-audit --no-fund
  npx playwright install chromium
  Pop-Location
}

$common = @(
  '--toc', '--toc-depth=2',
  "--resource-path=$docs",
  '--metadata', "title=$title",
  '--metadata', "subtitle=$subtitle"
)

& $pandoc $body @common -o (Join-Path $docs 'presentation.docx')
Write-Output 'presentation.docx'

$html = Join-Path $work 'presentation.html'
& $pandoc $body @common -s --embed-resources -c (Join-Path $docs 'print.css') -o $html

& node (Join-Path $docs 'to-pdf.mjs') $html (Join-Path $docs 'presentation.pdf')
