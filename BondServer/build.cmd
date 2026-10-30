@echo off
setlocal
set MAVEN_MULTI_MODULE_PROJECT_DIRECTORY=%~dp0
java -Dmaven.multiModuleProjectDirectory="%MAVEN_MULTI_MODULE_PROJECT_DIRECTORY%" -classpath "%~dp0.mvn\wrapper\maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain %*
