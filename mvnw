#!/bin/sh
# Maven wrapper for WellKnIT EIP — points to locally installed Maven 3.9.12
MAVEN_HOME="C:/Users/Hp/apache-maven/apache-maven-3.9.12"
JAVA_HOME="C:/Program Files/Java/jdk-25.0.2"
export JAVA_HOME
exec "$MAVEN_HOME/bin/mvn" "$@"
