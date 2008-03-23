package net.sf.alchim.winstone.mojo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

/**
 * Embed your warfile into the winstone JAR itself. This allows an all-in-one
 * container plus web-application JAR file to be downloaded, and then unpacked
 * at execution time.
 *
 * @goal embed
 * @phase package
 *
 * @author David Bernard
 * @see http://winstone.sourceforge.net/#embedding
 */
//@SuppressWarnings("unchecked")
public class EmbedMojo extends AbstractMojo {
    /**
     * Location of the file.
     *
     * @parameter expression="${project.build.directory}"
     * @required
     */
    private File outputDirectory;

    /**
     * filename of standalone jar.
     *
     * @parameter expression="${project.build.finalName}-standalone.jar"
     * @required
     */
    private String filename;

    /**
     * The War file to embed.
     *
     * @parameter expression="${project.build.directory}/${project.build.finalName}.war"
     * @required
     */
    private File warFile;

    /**
     * Properties to embed, If you need to add any default command-line arguments (eg ports or prefixes).
     * @see http://winstone.sourceforge.net/#commandLine
     * @parameter
     */
    private Properties cmdLineOptions;

    /**
     * @parameter expression="${project}"
     * @required
     */
    protected MavenProject project;

    /**
     * Used to look up Artifacts in the remote repository.
     *
     * @parameter expression="${component.org.apache.maven.artifact.factory.ArtifactFactory}"
     * @required
     * @readonly
     */
    protected ArtifactFactory factory;

    /**
     * Location of the local repository.
     *
     * @parameter expression="${localRepository}"
     * @readonly
     * @required
     */
    protected ArtifactRepository localRepository;

    /**
     * Used to look up Artifacts in the remote repository.
     *
     * @parameter expression="${component.org.apache.maven.artifact.resolver.ArtifactResolver}"
     * @required
     * @readonly
     */
    protected ArtifactResolver resolver;

    /**
     * List of Remote Repositories used by the resolver
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @readonly
     * @required
     */
    protected List remoteRepositories;

    public void execute() throws MojoExecutionException {
        if ("war".equals(project.getPackaging())) {
            getLog().info("work only for packaging == 'war'");
            return;
        }
        try {
            File f = outputDirectory;
            if (!f.exists()) {
                f.mkdirs();
            }
            embed();
        } catch (RuntimeException exc) {
            throw exc;
        } catch (MojoExecutionException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new MojoExecutionException("wrap: " + exc.getMessage(), exc);
        }
    }

    protected File findWinstoneJar() throws Exception {
        File back = null;
        Plugin me = (Plugin) project.getBuild().getPluginsAsMap().get("net.sf.alchim:winstone-maven-plugin");
        Iterator it = me.getDependencies().iterator();
        while (it.hasNext() && (back == null)) {
            Dependency dep = (Dependency) it.next();
            if (dep.getArtifactId().indexOf("winstone") > -1) {
                Artifact artifact = factory.createArtifactWithClassifier(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), dep.getType(), dep.getClassifier());
                back = selectWinstoneJar(artifact);
            }
        }
        if (back == null) {
            Artifact artifact = factory.createArtifactWithClassifier("net.sourceforge.winstone", "winstone", "0.9.6", "jar", "");
            back = selectWinstoneJar(artifact);
        }
        if (back == null) {
            throw new MojoFailureException("winstone artifact not found, please declare winstone artifact in plugin's dependencies");
        }
        return back;
    }

    private File selectWinstoneJar(Artifact artifact) throws Exception {
        resolver.resolve(artifact, remoteRepositories, localRepository);
        File found = new File(localRepository.getBasedir(), localRepository.pathOf(artifact));
        if (!found.exists()) {
            return null;
        }
        return found;
    }

    protected void embed() throws Exception {
        File outputFile = new File(outputDirectory, filename);
        JarOutputStream zos = new JarOutputStream(new FileOutputStream(outputFile));
        try {
            putWinstone(zos);
            putWarFile(zos);
            putCmdLineOptions(zos);
        } finally {
            getLog().info(outputFile + " created");
            close(zos);
        }
    }

    private void putWinstone(JarOutputStream zos) throws Exception {
        File file = findWinstoneJar();
        getLog().info("use winstone file: " + file.getName());
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(file);
            Enumeration entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry ientry = (JarEntry) entries.nextElement();
                getLog().debug("copying file: " + ientry.getName());

                JarEntry oentry = (JarEntry) ientry.clone();
                zos.putNextEntry(oentry);
                copyInto(jarFile.getInputStream(ientry), zos);
                zos.closeEntry();
            }
        } finally {
            close(jarFile);
        }
    }

    private void putWarFile(JarOutputStream zos) throws Exception {
        getLog().info("use war file: " + warFile.getName());
        JarEntry oentry = new JarEntry("embedded.war");
        zos.putNextEntry(oentry);
        copyInto(new FileInputStream(warFile), zos);
        zos.closeEntry();
    }

    private void putCmdLineOptions(JarOutputStream zos) throws Exception {
        if ((cmdLineOptions == null) || (cmdLineOptions.size() < 1)) {
            getLog().info("no cmd line options to embed");
            return;
        }
        JarEntry oentry = new JarEntry("embedded.properties");
        zos.putNextEntry(oentry);
        cmdLineOptions.store(zos, "embedded command line options for winstone");
        zos.closeEntry();
    }

    private void close(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch(IOException exc) {
                getLog().warn(exc);
            }
        }
    }

    private void close(OutputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch(IOException exc) {
                getLog().warn(exc);
            }
        }
    }

    private void close(JarFile stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch(IOException exc) {
                getLog().warn(exc);
            }
        }
    }

    private byte[] buffer_ = new byte[1024];
    private void copyInto(InputStream in, OutputStream out) throws IOException {
        try {
            int len;
            while ((len = in.read(buffer_)) >= 0) {
                out.write(buffer_, 0, len);
            }
        } finally {
            close(in);
            //close(out);
        }
    }

}
