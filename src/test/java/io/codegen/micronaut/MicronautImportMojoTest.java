package io.codegen.micronaut;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.testing.ArtifactStubFactory;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.ArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class MicronautImportMojoTest {
  @Rule
  public MojoRule rule =
      new MojoRule() {
        @Override
        protected void before() throws Throwable {}

        @Override
        protected void after() {}
      };

  private ArtifactStubFactory stubFactory;

  @Before
  public void setup() throws IOException {
    File baseDir = new File("target/unit-tests");
    stubFactory = new ArtifactStubFactory(baseDir, true);

    baseDir.mkdirs();

    try (ZipOutputStream stream =
        new ZipOutputStream(
            Files.newOutputStream(new File(baseDir, "stub.jar").toPath()),
            StandardCharsets.UTF_8)) {
      stream.putNextEntry(new ZipEntry("io/codegen/first/FirstClass.class"));
      stream.putNextEntry(new ZipEntry("io/codegen/second/SecondClass.class"));
      stream.putNextEntry(new ZipEntry("io/codegen/third/ThirdClass.class"));
    }

    stubFactory.setSrcFile(new File(baseDir, "stub.jar"));
  }

  /**
   * @throws Exception if any
   */
  @Test
  public void testSomething() throws Exception {
    File pom = new File("target/test-classes/project-to-test/");
    assertNotNull(pom);
    assertTrue(pom.exists());

    MavenProject project = rule.readMavenProject(pom);

    // Generate session
    MavenSession session = rule.newMavenSession(project);

    // Generate Execution and Mojo for testing
    MojoExecution execution = rule.newMojoExecution("generate-imports");
    MicronautImportMojo micronautImportMojo =
        (MicronautImportMojo) rule.lookupConfiguredMojo(session, execution);

    rule.setVariableValueToObject(micronautImportMojo, "artifactResolver", mockArtifactResolver());
    assertNotNull(micronautImportMojo);
    micronautImportMojo.execute();

    File outputDirectory =
        (File) rule.getVariableValueFromObject(micronautImportMojo, "outputDirectory");
    assertNotNull(outputDirectory);
    assertTrue(outputDirectory.exists());
  }

  private ArtifactResolver mockArtifactResolver() {
    return new ArtifactResolver() {
      @Override
      public ArtifactResult resolveArtifact(
          ProjectBuildingRequest projectBuildingRequest, Artifact artifact) {
        return () -> {
          try {
            return stubFactory.createArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                artifact.getScope(),
                artifact.getType(),
                artifact.getClassifier());
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        };
      }

      @Override
      public ArtifactResult resolveArtifact(
          ProjectBuildingRequest projectBuildingRequest, ArtifactCoordinate artifactCoordinate) {
        return () -> {
          try {
            return stubFactory.createArtifact(
                artifactCoordinate.getGroupId(),
                artifactCoordinate.getArtifactId(),
                artifactCoordinate.getVersion(),
                Artifact.SCOPE_COMPILE,
                artifactCoordinate.getExtension(),
                artifactCoordinate.getClassifier());
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        };
      }
    };
  }
}
