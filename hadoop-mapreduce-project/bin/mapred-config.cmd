@echo off
@rem Licensed to the Apache Software Foundation (ASF) under one or more
@rem contributor license agreements.  See the NOTICE file distributed with
@rem this work for additional information regarding copyright ownership.
@rem The ASF licenses this file to You under the Apache License, Version 2.0
@rem (the "License"); you may not use this file except in compliance with
@rem the License.  You may obtain a copy of the License at
@rem
@rem     http://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.

@rem included in all the hdfs scripts with source command
@rem should not be executed directly

if not defined HADOOP_BIN_PATH ( 
  set HADOOP_BIN_PATH=%~dp0
)

if "%HADOOP_BIN_PATH:~-1%" == "\" (
  set HADOOP_BIN_PATH=%HADOOP_BIN_PATH:~0,-1%
)

set DEFAULT_LIBEXEC_DIR=%HADOOP_BIN_PATH%\..\libexec
if not defined HADOOP_LIBEXEC_DIR (
  set HADOOP_LIBEXEC_DIR=%DEFAULT_LIBEXEC_DIR%
)

if not defined HADOOP_LOGFILE (
  set HADOOP_LOGFILE=mapred.log
)
set HADOOP_OPTS=%HADOOP_OPTS% -Dhadoop.log.file=%HADOOP_LOGFILE%

if exist %HADOOP_LIBEXEC_DIR%\hadoop-config.cmd (
  call %HADOOP_LIBEXEC_DIR%\hadoop-config.cmd %*
) else if exist %HADOOP_COMMON_HOME%\libexec\hadoop-config.cmd (
  call %HADOOP_COMMON_HOME%\libexec\hadoop-config.cmd %*
) else if exist %HADOOP_HOME%\libexec\hadoop-config.cmd (
  call %HADOOP_HOME%\libexec\hadoop-config.cmd %*
) else (
  echo Hadoop common not found.
)

:eof
