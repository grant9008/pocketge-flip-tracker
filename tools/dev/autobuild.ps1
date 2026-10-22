# Keeps this checkout up to date and built, on Windows: whenever the branch you
# are on moves on GitHub, it pulls and runs the same build you would by hand
# (gradlew clean build -x test). Start it once from the repository folder and
# leave the window open; when it says "ready", restart the client.
#
#   powershell -ExecutionPolicy Bypass -File tools\dev\autobuild.ps1
#   powershell -ExecutionPolicy Bypass -File tools\dev\autobuild.ps1 60    (check every 60s)
#
# It only fast-forwards. If a local change is in the way it lists the files and
# waits, rather than merging or throwing anything away; "git stash" sets them
# aside (and "git stash pop" brings them back), after which it carries on.
#
# The same thing for macOS and Linux is autobuild.sh, next to this file.

param([int]$Every = 20)

Set-Location (Join-Path $PSScriptRoot '..\..')

$branch = (git rev-parse --abbrev-ref HEAD | Out-String).Trim()
$remote = (git config "branch.$branch.remote" | Out-String).Trim()
if (-not $remote) { $remote = 'origin' }
$script:stuck = ''

function Stamp { (Get-Date).ToString('HH:mm:ss') }

function Head { (git log -1 --format='%h %s' | Out-String).Trim() }

function Invoke-Build
{
	Write-Host "[$(Stamp)] building $(Head)"
	& .\gradlew.bat clean build -x test -q
	if ($LASTEXITCODE -eq 0)
	{
		try { [console]::Beep(880, 200) } catch { }
		Write-Host "[$(Stamp)] ready: $(Head) - restart the client" -ForegroundColor Green
	}
	else
	{
		Write-Host "[$(Stamp)] build failed; trying again on the next push (or close the client and run gradlew yourself)" -ForegroundColor Red
	}
}

# True when there was something new and it is now checked out.
function Update-Checkout
{
	git fetch -q $remote $branch 2>$null
	if ($LASTEXITCODE -ne 0) { return $false }
	$here = (git rev-parse HEAD | Out-String).Trim()
	$there = (git rev-parse "$remote/$branch" | Out-String).Trim()
	if ($here -eq $there) { return $false }
	git merge --ff-only -q "$remote/$branch" 2>$null | Out-Null
	if ($LASTEXITCODE -ne 0)
	{
		if ($script:stuck -ne $there)
		{
			$script:stuck = $there
			Write-Host "[$(Stamp)] can't update to $remote/$branch - these local changes are in the way:" -ForegroundColor Yellow
			# To the screen, not the pipeline: anything left on the pipeline
			# would become part of this function's return value.
			git status --short | Out-Host
			Write-Host "  Run 'git stash' to set them aside ('git stash pop' brings them back); this then carries on by itself."
		}
		return $false
	}
	$script:stuck = ''
	return $true
}

Write-Host "Watching $remote/$branch every ${Every}s. Ctrl+C to stop."
Update-Checkout | Out-Null
Invoke-Build
while ($true)
{
	Start-Sleep -Seconds $Every
	if (Update-Checkout) { Invoke-Build }
}
