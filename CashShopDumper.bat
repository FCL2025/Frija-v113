@echo off
title CashShopDumper
set CLASSPATH=.;dist\TMS113.jar;dist\lib\*
java -Xmx2G -Xms512M -Dnet.sf.odinms.wzpath=wz tools.wztosql.CashShopDumper
pause
