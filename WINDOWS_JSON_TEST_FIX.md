# FastFrame v0.7.1 — Windows JSON test correction

The compilation failure in `demo-app` is a test failure, not a server bug.
`Map.of()` does not guarantee the order of JSON keys, so a raw JSON string
comparison was not portable across Java runtimes.

## Apply only the patch

From Windows PowerShell:

```powershell
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\fastframe-v0.7.1-json-test-patch.zip" -DestinationPath "C:\xampp\www\fastframe" -Force
cd C:\xampp\www\fastframe
.\scripts\run-demo.ps1
```

## Verify without running server

```powershell
mvn -B -ntp -pl demo-app -am clean verify
```

The fix changes only the demo test and notes; no runtime framework classes were modified.
