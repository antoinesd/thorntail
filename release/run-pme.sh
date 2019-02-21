#!/bin/bash

BASE_URL=http://repo1.maven.org/maven2/org/commonjava/maven/ext/pom-manipulation-cli
VERSION=$(curl --silent $BASE_URL/maven-metadata.xml | xmlstarlet select --template --value-of '/metadata/versioning/latest')
FILE=pom-manipulation-cli-$VERSION.jar
if [[ ! -f $FILE ]] ; then
  echo "Downloading $FILE"
  curl --silent --remote-name $BASE_URL/$VERSION/$FILE
  echo "Downloaded $FILE"
else
  echo "Using existing $FILE"
fi

java -jar $FILE -DdependencySource=REST -DversionIncrementalSuffix=redhat -DrestURL=http://da.psi.redhat.com/da/rest/v-1 "$@"
