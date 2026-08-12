@echo off
rem Build the self-contained grog-odoo uberjar (target\grog-odoo.jar).
rem Requires the Clojure CLI only on the *build* machine; the resulting jar
rem runs on any machine with a JRE 17+ (`java -jar grog-odoo.jar`).
cd /d "%~dp0"
if not exist target mkdir target
clojure -T:build uber
echo Built: %CD%\target\grog-odoo.jar