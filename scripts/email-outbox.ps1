[CmdletBinding()]
param(
    [ValidateSet('list','retry','cancel')][string]$Action='list',
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,62}$')][string]$Project='commonbeacon',
    [guid]$Id=[guid]::Empty,
    [ValidateRange(0,9223372036854775807)][long]$ExpectedVersion=0
)
$ErrorActionPreference='Stop'
if($Action -ne 'list' -and ($Id -eq [guid]::Empty -or -not $PSBoundParameters.ContainsKey('ExpectedVersion'))){
    throw 'Retry/cancel require an outbox UUID and its current lease version from list.'
}
# Inspect the explicitly selected deployment; do not read .env/.aws or print credentials.
$dbIds=@(& docker ps -q --filter "label=com.docker.compose.project=$Project" --filter 'label=com.docker.compose.service=db')
if($LASTEXITCODE -ne 0 -or $dbIds.Count -ne 1 -or $dbIds[0] -notmatch '^[a-f0-9]{12,64}$'){throw 'Exactly one running database for the selected project is required.'}
$sql="SET lock_timeout='5s'; SET statement_timeout='10s';`n"
if($Action -eq 'list'){
    $sql+='SELECT id,message_type,state,attempts,failure_code,lease_version,next_attempt_at,expires_at FROM email_outbox ORDER BY updated_at DESC,id LIMIT 20;'
}else{
    # Values are validated enums/UUID/integer, never arbitrary SQL or destination text.
    $sql+="SELECT operate_email_outbox('$($Id.ToString())'::uuid,$ExpectedVersion,'$Action') AS applied;"
}
$previousPreference=$ErrorActionPreference
$ErrorActionPreference='Continue'
try{
    $sql | & docker exec -i $dbIds[0] sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f -'
    $code=$LASTEXITCODE
}finally{$ErrorActionPreference=$previousPreference}
if($code -ne 0){throw "Outbox command failed (exit $code)."}
