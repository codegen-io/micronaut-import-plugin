package io.codegen.micronaut;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;

/** Generate import factories from the project dependencies. */
@Mojo(
    name = "generate-imports",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    threadSafe = true,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class MicronautImportMojo extends AbstractMojo {

  /** The Maven session */
  @Parameter(defaultValue = "${session}", readonly = true)
  MavenSession session;

  /** Reference to the Maven project on which the plugin is invoked. */
  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  MavenProject project;

  /** Remote repositories which will be searched for artifacts. */
  @Parameter(
      defaultValue = "${project.remoteArtifactRepositories}",
      readonly = true,
      required = true)
  @SuppressWarnings("deprecation")
  private List<ArtifactRepository> remoteRepositories;

  @Component private ArtifactResolver artifactResolver;

  /** Location of the output files. */
  @Parameter(
      defaultValue = "${project.build.directory}/generated-sources/plugin",
      property = "outputDir",
      required = true)
  File outputDirectory;

  /**
   * Add the output directory to the project as a source root in order to let the generated java
   * classes be compiled and included in the project artifact.
   */
  @Parameter(defaultValue = "true")
  private boolean addCompileSourceRoot = true;

  /** Regexp pattern which allows including certain dependencies. */
  @Parameter(defaultValue = "^.*:.*$", required = true)
  String includeDependenciesFilter;

  /** Regexp pattern which allows excluding certain dependencies. */
  @Parameter(defaultValue = "^$", required = true)
  String excludeDependenciesFilter;

  /** Regexp pattern which allows including certain packages. */
  @Parameter(defaultValue = "^.*$", required = true)
  String includePackageFilter;

  /** Regexp pattern which allows excluding certain packages. */
  @Parameter(defaultValue = "^$", required = true)
  String excludePackageFilter;

  /**
   * The package name which is used for the generated import factories. When not specified a factory
   * is generated for each package within that package in order to access package protected fields.
   */
  @Parameter String targetPackage;

  public void execute() throws MojoExecutionException {
    Pattern includeDependency = Pattern.compile(includeDependenciesFilter);
    Pattern excludeDependency = Pattern.compile(excludeDependenciesFilter);
    Pattern includePackage = Pattern.compile(includePackageFilter);
    Pattern excludePackage = Pattern.compile(excludePackageFilter);

    if (addCompileSourceRoot) {
      project.addCompileSourceRoot(outputDirectory.getPath());
    }

    List<Dependency> dependencies =
        project.getDependencies().stream()
            .filter(dependency -> includeDependency.matcher(getIdentifier(dependency)).matches())
            .filter(dependency -> !excludeDependency.matcher(getIdentifier(dependency)).matches())
            .collect(Collectors.toList());

    getLog().info("Number of matching dependencies: " + dependencies.size());

    dependencies.stream()
        .map(dependency -> dependency.getGroupId() + ":" + dependency.getArtifactId())
        .sorted()
        .forEach(dependency -> getLog().info(" " + dependency));

    List<String> packages = new ArrayList<>();
    for (Dependency dependency : dependencies) {
      packages.addAll(getPackages(dependency));
    }

    List<String> filtered =
        packages.stream()
            .filter(includePackage.asPredicate())
            .filter(excludePackage.asPredicate().negate())
            .collect(Collectors.toList());

    getLog().info("Filtered Packages:\n" + String.join("\n", filtered));

    if (targetPackage == null || targetPackage.isEmpty()) {
      for (String packageName : filtered) {
        try {
          generateImportFactory(packageName, Collections.singletonList(packageName));
        } catch (IOException e) {
          throw new MojoExecutionException("Error creating factory for " + packageName, e);
        }
      }
    } else {
      try {
        generateImportFactory(targetPackage, filtered);
      } catch (IOException e) {
        throw new MojoExecutionException("Error creating factory for " + targetPackage, e);
      }
    }
  }

  private List<String> getPackages(Dependency dependency) throws MojoExecutionException {
    String artifactCoords =
        dependency.getGroupId()
            + ":"
            + dependency.getArtifactId()
            + ":"
            + dependency.getType()
            + ":"
            + dependency.getVersion();

    getLog().info(" processing " + artifactCoords);

    ArtifactCoordinate coordinates = toCoordinate(dependency);

    ProjectBuildingRequest buildingRequest =
        new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
    buildingRequest.setRemoteRepositories(remoteRepositories);

    Artifact artifact;
    try {
      artifact = artifactResolver.resolveArtifact(buildingRequest, coordinates).getArtifact();
    } catch (ArtifactResolverException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }

    getLog().info("Resolved artifact " + artifact + " to " + artifact.getFile());

    try (JarFile file = new JarFile(artifact.getFile(), false)) {
      return file.stream()
          .filter(entry -> entry.getName().endsWith(".class"))
          .map(this::getPackageName)
          .distinct()
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new MojoExecutionException("Unable to read " + artifact.getFile(), e);
    }
  }

  private String getIdentifier(Dependency dependency) {
    return dependency.getGroupId() + ":" + dependency.getArtifactId();
  }

  private String getPackageName(JarEntry entry) {
    String entryName = entry.getName();
    // remove .class from the end and change format to use periods instead of forward slashes
    return entryName.substring(0, entryName.lastIndexOf('/')).replace('/', '.');
  }

  private ArtifactCoordinate toCoordinate(Dependency dependency) {
    DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
    coordinate.setGroupId(dependency.getGroupId());
    coordinate.setArtifactId(dependency.getArtifactId());
    coordinate.setVersion(dependency.getVersion());
    coordinate.setExtension(dependency.getType());
    coordinate.setClassifier(dependency.getClassifier());
    return coordinate;
  }

  private void generateImportFactory(String packageName, List<String> packages) throws IOException {
    Path factoryPath = resolvePath(packageName).resolve("ImportFactory.java");
    List<String> code = new ArrayList<>();
    code.add("package " + packageName + ";");
    code.add("");
    code.add("import io.micronaut.context.annotation.Factory;");
    code.add("import io.micronaut.context.annotation.Import;");
    code.add("");
    code.add("/** Factory which allows Micronaut to import beans from the specified packages. */");
    code.add("@Factory");
    code.add("@Import(");
    code.add("    packages = {");
    for (String name : packages) {
      code.add("      \"" + name + "\",");
    }
    code.add("    })");
    code.add("public class ImportFactory {}\n");

    Files.createDirectories(factoryPath.getParent());
    Files.write(
        factoryPath,
        code,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private Path resolvePath(String packageName) {
    return outputDirectory.toPath().resolve(packageName.replace('.', '/'));
  }
}
