@echo off
title TMS113
rem JDK 26 is required. dist\lib holds MINA, Nashorn (dropped from the JDK in
rem 15, so it ships here), the asm jars Nashorn needs, MySQL Connector/J and
rem slf4j. The working directory has to stay the repo root: Settings.ini,
rem Libs\, Logs\ and the wz path are all resolved relative to it.
set CLASSPATH=.;dist\TMS113.jar;dist\lib\*
java -Xmx4G -Xms2G -Dnet.sf.odinms.wzpath=wz server.Start
pause
