# Micronaut Import Maven Plugin

## Overview
The Micronaut Import Maven Plugin allows one to import beans from the projects dependencies. This
is particularly useful when dependencies which are external to the project define beans in various
packages.

## How do I use this?
Just define this plugin in your Maven pom.xml and configure which artifacts and packages must be
included:

```
<plugin>
  <groupId>io.codegen.micronaut</groupId>
  <artifactId>micronaut-import-plugin</artifactId>
  <version>1.0.0</version>
  <configuration>
    <includeDependenciesFilter>^maven-group-id:maven-artifact-id$</includeDependenciesFilter>
    <includePackageFilter>^java.package.name.*$</includePackageFilter>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>generate-imports</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

## But what do I get?
By default, a factory class is generated for each matching java package in the matching
dependencies, which allows Micronaut to directly access package protected fields:

```
package java.packagename;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Import;

/** Factory which allows Micronaut to import beans from the specified packages. */
@Factory
@Import(
    packages = {
      "java.packagename",
    })
public class ImportFactory {}
```

When the property `targetPackage` is set a factory is generated in this package with all the
matching packages:
```
package target.package;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Import;

/** Factory which allows Micronaut to import beans from the specified packages. */
@Factory
@Import(
    packages = {
      "some.package",
      "another.package",
    })
public class ImportFactory {}
```

## Releasing

Create and deploy the release artifacts:
```
export GPG_TTY=$(tty)

NEXT_VERSION=$(mvn help:evaluate -Dexpression="jgitver.next_patch_version" -q -DforceStdout)
git tag -a -m "Release v${NEXT_VERSION}" "${NEXT_VERSION}"
mvn clean deploy -Psonatype-oss-release
```

Open [Sonatype OSS](https://oss.sonatype.org/) and close and release the created staging repository.

Push the tag to github:
```
git push --follow-tags origin master
```
