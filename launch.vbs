Option Explicit
' midEditor silent launcher: builds the jar if missing, then starts the app without a console window.
Dim fso, shell, base, jar, javaHome, javaExe

Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

base = fso.GetParentFolderName(WScript.ScriptFullName)
jar = base & "\target\mid-editor-1.0.0.jar"

If Not fso.FileExists(jar) Then
    shell.CurrentDirectory = base
    shell.Run "cmd /c mvn -B -q package", 0, True
End If

If Not fso.FileExists(jar) Then
    MsgBox "Build failed: " & jar & vbCrLf & vbCrLf & _
           "Please make sure Maven and the JDK are available, then try again.", _
           vbCritical, "midEditor"
    WScript.Quit 1
End If

javaHome = shell.ExpandEnvironmentStrings("%JAVA_HOME%")
If javaHome = "%JAVA_HOME%" Then
    javaHome = "C:\Program Files\Java\jdk-21.0.11"
End If
javaExe = javaHome & "\bin\javaw.exe"
If Not fso.FileExists(javaExe) Then
    MsgBox "Cannot find " & javaExe & vbCrLf & _
           "Please install a JDK 21+ or set JAVA_HOME.", vbCritical, "midEditor"
    WScript.Quit 1
End If

shell.CurrentDirectory = base
shell.Run """" & javaExe & """ -jar """ & jar & """", 1, False
