# Specify the log file path
$logFile = "C:\Users\josef\logfile.txt"

# Initialize an empty array to hold the results
$results = @()

# Custom function to log debug information to a file
function Write-DebugToFile($message)
{
    $message | Out-File -Append -FilePath $logFile
}

$input | ForEach-Object {
    if (Test-Path $_)
    {
        Write-DebugToFile "Processing path: $_"

        # Check if it's a directory
        if (Test-Path -Path $_ -PathType Container)
        {
            Write-DebugToFile "Directory found, processing files recursively"

            # It's a directory, so recursively get all files
            $files = Get-ChildItem -Path $_ -Recurse -File
            foreach ($file in $files)
            {
                Write-DebugToFile "Processing file: $( $file.FullName )"

                $hash = Get-FileHash -Path $file.FullName -Algorithm SHA256

                # Parse attributes and create a human-readable list
                $attributeNames = [enum]::GetValues([System.IO.FileAttributes]) | Where-Object { ($file.Attributes -band $_) -eq $_ }
                $attributes = $attributeNames -join ","

                # Prepare the result with additional attributes
                $result = @{
                    Exists = $true
                    Size = $file.Length
                    LastModified = $file.LastWriteTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ", [System.Globalization.CultureInfo]::InvariantCulture)
                    CreationTime = $file.CreationTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ", [System.Globalization.CultureInfo]::InvariantCulture)
                    LastAccessTime = $file.LastAccessTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ", [System.Globalization.CultureInfo]::InvariantCulture)
                    Attributes = $attributes
                    Hash = $hash.Hash
                    FilePath = $file.FullName
                }

                # Add the result to the results array
                $results += $result
            }
        }
        else
        {
            Write-DebugToFile "Processing file directly: $_"

            # It's a file, process it directly
            $file = Get-Item $_
            $hash = Get-FileHash -Path $file.FullName -Algorithm SHA256

            # Parse attributes and create a human-readable list
            $attributeNames = [enum]::GetValues([System.IO.FileAttributes]) | Where-Object { ($file.Attributes -band $_) -eq $_ }
            $attributes = $attributeNames -join ","

            # Prepare the result with additional attributes
            $result = @{
                Exists = $true
                Size = $file.Length
                LastModified = $file.LastWriteTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ", [System.Globalization.CultureInfo]::InvariantCulture)
                CreationTime = $file.CreationTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ", [System.Globalization.CultureInfo]::InvariantCulture)
                LastAccessTime = $file.LastAccessTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ", [System.Globalization.CultureInfo]::InvariantCulture)
                Attributes = $attributes
                Hash = $hash.Hash
                FilePath = $file.FullName
            }

            # Add the result to the results array
            $results += $result
        }
    }
    else
    {
        Write-DebugToFile "Path does not exist: $_"

        # If path does not exist
        $result = @{
            Exists = $false
            FilePath = $_
        }

        # Add the result to the results array
        $results += $result
    }

    # Output all results as a single JSON array at the end
    Write-DebugToFile "All paths processed, outputting results"
    Write-Host $( $results | ConvertTo-Json -Depth 3 -Compress )
    Write-DebugToFile "Results outputted"
    # Clear the results array for the next iteration
    $results = @()
}
