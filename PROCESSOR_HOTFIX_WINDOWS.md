# Windows processor build hotfix (FastFrame v0.7.1)

Fixes `Bad service configuration file: Provider dev.fastframe.processor.FastFrameProcessor not found` during the build of `framework-processor`.

## What changed

- `framework-processor/pom.xml`: disables annotation processing **only for framework-processor** (`<proc>none</proc>`).
- Moved the `javax.annotation.processing.Processor` service descriptor from `src/main/resources` to `src/main/processor-descriptor`.
- The POM **excludes the old descriptor**, even if the old file is left behind when extracting over an existing project.
- Maven copies the descriptor into `target/classes` during `process-classes`, after javac has compiled `FastFrameProcessor`.
- `demo-app` and the benchmark module continue using the processor during compilation.
- `run-demo.ps1` checks the POM at the **actual** project root to catch old copies.

## Important: correct folder

From PowerShell:

```powershell
cd C:\xampp\www\fastframe
Select-String -Path .\framework-processor\pom.xml -Pattern '<proc>none</proc>'
Test-Path .\framework-processor\src\main\processor-descriptor\META-INF\services\javax.annotation.processing.Processor
```

Both checks should return results; `Test-Path` should print `True`.
If you extracted into `C:\xampp\www\fastframe\fastframe-v0.7.1\`, then you may be building the wrong copy.

## Rebuild

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\run-demo.ps1
```

Or test the module in isolation (requires JDK 21 and Maven):

```powershell
mvn -B -ntp -pl framework-processor -am clean package
jar tf .\framework-processor\target\framework-processor-0.7.1-SNAPSHOT.jar | Select-String 'META-INF/services/javax.annotation.processing.Processor'
mvn -B -ntp -pl demo-app -am clean package
```

The archive is intentionally packaged with files directly at its root, without an extra `fastframe-v0.7.1/` directory. Extract the files **into your existing project root** and allow replacement of corresponding files. Back up or commit changes first.

This patch does not change your machine-wide JAVA_HOME or Java 8 installation.
