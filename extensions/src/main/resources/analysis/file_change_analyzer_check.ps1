$input | ForEach-Object {
    if (Test-Path $_)
    {
        $file = Get-Item $_
        #        $hash = Get-FileHash -Path $file.FullName -Algorithm SHA256

        # Parse attributes and create a human-readable list
        $attributeNames = [enum]::GetValues([System.IO.FileAttributes]) | Where-Object { ($file.Attributes -band $_) -eq $_ }
        $attributes = $attributeNames -join ","

        # Prepare the result with additional attributes
        $result = @{
            Exists = $true
            Size = $file.Length
            LastModified = $file.LastWriteTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ")
            CreationTime = $file.CreationTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ")
            LastAccessTime = $file.LastAccessTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ")
            Attributes = $attributes
            Hash = $hash.Hash
        }
    }
    else
    {
        $result = @{
            Exists = $false
        }
    }

    # Output each result as JSON immediately
    Write-Host "$( $result | ConvertTo-Json -Depth 2 -Compress )"
}
